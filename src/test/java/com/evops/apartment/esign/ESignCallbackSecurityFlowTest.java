package com.evops.apartment.esign;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.esign.web.ESignCallbackFilter;
import com.evops.apartment.task.TaskProperties;
import com.evops.apartment.task.engine.SignArchiveStepHandler;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-9 电子签章平台回调安全与断点唤醒端到端：
 *
 * 1. 合法签名回调即时唤醒停在「等待租客签署」（PENDING 55%）的任务，随后 SUCCESS；
 * 2. 时间戳超 8 分钟（服务端时钟）拒绝；
 * 3. 错签名拒绝；同一 nonce 15 分钟内重放拒绝；
 * 4. 不同 nonce 重复回调业务幂等（两次都受理，不产生重复推进）；
 * 5. 签名覆盖全部参数含空值：去掉/改动空值字段即验签失败；
 * 6. 缺签名头拒绝；验签通过但业务号无进行中任务 → 400。
 *
 * 回调为机器对机器，测试直接按平台约定用共享密钥算签名，模拟真实平台入站。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_apt9_test;DB_CLOSE_DELAY=-1",
        "evops.task.dispatch-interval-ms=100",
        "evops.task.dispatch-initial-delay-ms=100",
        "evops.task.sign-poll-interval-ms=1000",
        "evops.esign.mock.step-delay-ms=0",
        "evops.esign.mock.sign-wait-ms=600000",
        // 平台回调验签密钥：与出站同把；非空才放行（fail-closed）
        "evops.esign.access-key=test-access-key",
        "evops.esign.access-secret=test-access-secret"
})
@AutoConfigureMockMvc
class ESignCallbackSecurityFlowTest {
    private static final String CALLBACK_PATH = "/api/esign/callback/sign";
    private static final String ACCESS_KEY = "test-access-key";
    private static final String ACCESS_SECRET = "test-access-secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockESignPlatformClient mockESign;
    @Autowired private AsyncTaskMapper taskMapper;
    @Autowired private TaskProperties taskProperties;

    private String stewardToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() throws Exception {
        mockESign.resetForTest();
        stewardToken = login("steward01", "steward123");
        supervisorToken = login("supervisor01", "supervisor123");
    }

    /**
     * 每个用例后收尾：本类多用 holdSign 把任务钉在「等待租客签署」，被安全用例拒绝回调的任务
     * 不会走完。直接把残留的 HT-CB-* 等待/在途任务置 CANCELLED，避免跨测试被调度继续领取。
     */
    @AfterEach
    void tearDown() {
        AsyncTask terminal = new AsyncTask();
        terminal.setStatus(AsyncTask.STATUS_CANCELLED);
        terminal.setCancelRequested(true);
        taskMapper.update(terminal, new QueryWrapper<AsyncTask>()
                .eq("task_type", AsyncTask.TYPE_SIGN_ARCHIVE)
                .likeRight("biz_ref", "HT-CB-")
                .in("status", java.util.Arrays.asList(
                        AsyncTask.STATUS_PENDING, AsyncTask.STATUS_RUNNING)));
    }

    @Test
    void validCallback_wakesWaitingTask_andRunsToSuccess() throws Exception {
        registerLedger(supervisorToken, "PROP-CB-001", "示例小区");
        String contractNo = "HT-CB-WAKE-0001";
        mockESign.holdSign(SignArchiveStepHandler.toBizNo(contractNo));

        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-CB-001");
        awaitProgress(stewardToken, taskNo, AsyncTask.STATUS_PENDING, 55, 20000);

        // 回调瞬间关调度：任务确定停在 PENDING/55%，唤醒 CAS 必命中（woken=true），不与轮询领取竞态
        taskProperties.setEnabled(false);
        Thread.sleep(200); // 等关调度前已在途的工作线程执行完第 12 步 WAIT、停回 PENDING
        MvcResult cb;
        try {
            String body = callbackBody(contractNo, "");
            cb = mockMvc.perform(signedCallback(body, System.currentTimeMillis(), newNonce()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.received").value(true))
                    .andExpect(jsonPath("$.data.woken").value(true))
                    .andExpect(jsonPath("$.data.contractNo").value(contractNo))
                    .andReturn();
        } finally {
            taskProperties.setEnabled(true);
        }
        assertTrue(objectMapper.readTree(cb.getResponse().getContentAsString())
                .path("data").path("taskNo").asText().contains("TASK-"));

        // 唤醒后调度立即续跑剩余步骤至成功（轮询兜底也在，但唤醒使其即时推进）
        JsonNode success = awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);
        assertEquals(100, success.path("progress").asInt());
        assertEquals(20, success.path("currentStep").asInt());
    }

    @Test
    void timestampOver8Minutes_rejectedByServerClock() throws Exception {
        registerLedger(supervisorToken, "PROP-CB-002", "示例小区");
        String contractNo = "HT-CB-EXPIRED-0001";
        mockESign.holdSign(SignArchiveStepHandler.toBizNo(contractNo));
        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-CB-002");
        awaitProgress(stewardToken, taskNo, AsyncTask.STATUS_PENDING, 55, 20000);

        taskProperties.setEnabled(false);
        Thread.sleep(200);
        try {
            // 9 分钟前的时间戳（签名本身按该时间戳正确计算），超 8 分钟时效 → 拒绝
            long staleTs = System.currentTimeMillis() - 9L * 60 * 1000;
            mockMvc.perform(signedCallback(callbackBody(contractNo, ""), staleTs, newNonce()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("时效")));

            // 未来 9 分钟同样拒绝（时钟漂移/伪造）
            long futureTs = System.currentTimeMillis() + 9L * 60 * 1000;
            mockMvc.perform(signedCallback(callbackBody(contractNo, ""), futureTs, newNonce()))
                    .andExpect(status().isUnauthorized());

            // 任务未被推进：仍停在等待态
            JsonNode still = getTask(stewardToken, taskNo);
            assertEquals(AsyncTask.STATUS_PENDING, still.path("status").asText());
            assertEquals(55, still.path("progress").asInt());
        } finally {
            taskProperties.setEnabled(true);
        }
    }

    @Test
    void wrongSignature_rejected() throws Exception {
        registerLedger(supervisorToken, "PROP-CB-003", "示例小区");
        String contractNo = "HT-CB-BADSIGN-0001";
        mockESign.holdSign(SignArchiveStepHandler.toBizNo(contractNo));
        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-CB-003");
        awaitProgress(stewardToken, taskNo, AsyncTask.STATUS_PENDING, 55, 20000);

        String body = callbackBody(contractNo, "");
        long ts = System.currentTimeMillis();
        String nonce = newNonce();
        String signature = signWith("wrong-secret", body, ts, nonce);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ESignCallbackFilter.HEADER_KEY, ACCESS_KEY)
                        .header(ESignCallbackFilter.HEADER_TIMESTAMP, String.valueOf(ts))
                        .header(ESignCallbackFilter.HEADER_NONCE, nonce)
                        .header(ESignCallbackFilter.HEADER_SIGNATURE, signature)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void replaySameNonce_withinWindow_rejected() throws Exception {
        registerLedger(supervisorToken, "PROP-CB-004", "示例小区");
        String contractNo = "HT-CB-REPLAY-0001";
        mockESign.holdSign(SignArchiveStepHandler.toBizNo(contractNo));
        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-CB-004");
        awaitProgress(stewardToken, taskNo, AsyncTask.STATUS_PENDING, 55, 20000);

        // 关调度：让任务稳定停在等待态，回调唤醒后不被立即领走，第二次重放确定命中 nonce 唯一约束
        taskProperties.setEnabled(false);
        try {
            String body = callbackBody(contractNo, "");
            long ts = System.currentTimeMillis();
            String nonce = newNonce();
            // 第一次受理
            mockMvc.perform(signedCallback(body, ts, nonce))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.woken").value(true));
            // 同一 nonce + 完全相同报文重放 → 401
            mockMvc.perform(signedCallback(body, ts, nonce))
                    .andExpect(status().isUnauthorized())
                    .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("nonce"),
                            "应提示 nonce 重放"));
        } finally {
            taskProperties.setEnabled(true);
        }
    }

    @Test
    void idempotentCallback_differentNonce_bothAcceptedNoDuplicateAdvance() throws Exception {
        registerLedger(supervisorToken, "PROP-CB-005", "示例小区");
        String contractNo = "HT-CB-IDEM-0001";
        mockESign.holdSign(SignArchiveStepHandler.toBizNo(contractNo));
        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-CB-005");
        awaitProgress(stewardToken, taskNo, AsyncTask.STATUS_PENDING, 55, 20000);

        // 关调度，两次回调（不同 nonce）都到达同一等待任务：业务幂等，不报错、不重复推进步骤
        taskProperties.setEnabled(false);
        try {
            mockMvc.perform(signedCallback(callbackBody(contractNo, ""), System.currentTimeMillis(), newNonce()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.woken").value(true));
            mockMvc.perform(signedCallback(callbackBody(contractNo, ""), System.currentTimeMillis(), newNonce()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.woken").value(true));

            AsyncTask waiting = taskMapper.selectOne(new QueryWrapper<AsyncTask>().eq("task_no", taskNo));
            assertEquals(AsyncTask.STATUS_PENDING, waiting.getStatus());
            assertEquals(11, waiting.getCurrentStep(), "回调只唤醒不改进度，仍停在第 11 步边界");
        } finally {
            taskProperties.setEnabled(true);
        }

        // 恢复调度后任务续跑到成功（mock 已被回调置为签署态）
        JsonNode success = awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);
        assertEquals(100, success.path("progress").asInt());
    }

    @Test
    void signatureCoversAllParamsIncludingEmptyValues() throws Exception {
        registerLedger(supervisorToken, "PROP-CB-006", "示例小区");
        String contractNo = "HT-CB-EMPTY-0001";
        mockESign.holdSign(SignArchiveStepHandler.toBizNo(contractNo));
        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-CB-006");
        awaitProgress(stewardToken, taskNo, AsyncTask.STATUS_PENDING, 55, 20000);

        // 报文中 remark 为空串，空值字段也在 bodyMd5 内：按原文签名合法
        String withEmpty = callbackBody(contractNo, "");
        assertTrue(withEmpty.contains("\"remark\":\"\""), "用例前置：报文确含空值字段");
        mockMvc.perform(signedCallback(withEmpty, System.currentTimeMillis(), newNonce()))
                .andExpect(status().isOk());

        // 同一套头（同签名）但把空值改成非空：报文一个字节变化即验签失败
        // 用新任务/新 nonce 构造一次“签名与 body 不符”
        String contractNo2 = "HT-CB-EMPTY-0002";
        mockESign.holdSign(SignArchiveStepHandler.toBizNo(contractNo2));
        registerLedger(supervisorToken, "PROP-CB-006B", "示例小区");
        String taskNo2 = submitAndGetTaskNo(stewardToken, contractNo2, "PROP-CB-006B");
        awaitProgress(stewardToken, taskNo2, AsyncTask.STATUS_PENDING, 55, 20000);

        String signedBody = callbackBody(contractNo2, "");                 // 按空 remark 算签名
        String tamperedBody = callbackBody(contractNo2, "已被改动");        // 实际发送非空
        long ts = System.currentTimeMillis();
        String nonce = newNonce();
        String sig = signWith(ACCESS_SECRET, signedBody, ts, nonce);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ESignCallbackFilter.HEADER_KEY, ACCESS_KEY)
                        .header(ESignCallbackFilter.HEADER_TIMESTAMP, String.valueOf(ts))
                        .header(ESignCallbackFilter.HEADER_NONCE, nonce)
                        .header(ESignCallbackFilter.HEADER_SIGNATURE, sig)
                        .content(tamperedBody))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("签名")));
    }

    @Test
    void missingSignatureHeaders_rejected() throws Exception {
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackBody("HT-CB-NOHEAD-0001", "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void signedButUnknownBizNo_rejectedAsBusinessError() throws Exception {
        // 头与签名都合法（过过滤器、写入 nonce），但业务号无进行中任务 → 400
        String body = callbackBody("HT-CB-UNKNOWN-0001", "");
        MvcResult unknown = mockMvc.perform(signedCallback(body, System.currentTimeMillis(), newNonce()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn();
        // 控制器 400 经 Jackson 以 UTF-8 输出但未显式带 charset，MockMvc 默认按 ISO-8859-1 解码会乱码，
        // 故按字节 UTF-8 还原后断言中文消息（RFC 8259：JSON 默认编码即 UTF-8）
        String resp = new String(unknown.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(resp.contains("无进行中"), "实际响应: " + resp);
    }

    // ---------------- 平台侧签名/请求构造 ----------------

    /** 按平台约定构造带全套签名头的回调请求。 */
    private MockHttpServletRequestBuilder signedCallback(String body, long timestampMs, String nonce) {
        String signature = signWith(ACCESS_SECRET, body, timestampMs, nonce);
        return post(CALLBACK_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ESignCallbackFilter.HEADER_KEY, ACCESS_KEY)
                .header(ESignCallbackFilter.HEADER_TIMESTAMP, String.valueOf(timestampMs))
                .header(ESignCallbackFilter.HEADER_NONCE, nonce)
                .header(ESignCallbackFilter.HEADER_SIGNATURE, signature)
                .content(body);
    }

    /** 与服务端同算法：HMAC-SHA256(METHOD\nPATH\nts\nnonce\nmd5(body))。 */
    private String signWith(String secret, String body, long timestampMs, String nonce) {
        String md5 = ESignSignature.md5Hex(body);
        return ESignSignature.sign(secret, "POST", CALLBACK_PATH, String.valueOf(timestampMs), nonce, md5);
    }

    private String callbackBody(String contractNo, String remark) {
        String bizNo = SignArchiveStepHandler.toBizNo(contractNo);
        return "{\"event\":\"SIGN_COMPLETED\",\"bizNo\":\"" + bizNo + "\","
                + "\"flowNo\":\"\",\"signedAt\":\"2026-09-24 18:00:00\","
                + "\"remark\":\"" + remark + "\"}";
    }

    private static String newNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ---------------- 业务侧 helpers（同 SignArchiveTaskFlowTest 口径） ----------------

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private void registerLedger(String token, String propertyCode, String community) throws Exception {
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyCode\":\"" + propertyCode + "\",\"community\":\"" + community
                                + "\",\"building\":\"1栋\",\"roomNo\":\"101\",\"area\":50.5,"
                                + "\"requestNo\":\"REQ-" + propertyCode + "\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private String submitAndGetTaskNo(String token, String contractNo, String propertyCode) throws Exception {
        String body = "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"" + propertyCode + "\","
                + "\"tenantName\":\"张三\",\"tenantPhone\":\"13800000000\",\"tenantIdCard\":\"110101199001011234\","
                + "\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                + "\"monthlyRent\":3000,\"deposit\":1000,"
                + "\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"ignored\"}";
        MvcResult r = mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("taskNo").asText();
    }

    private JsonNode getTask(String token, String taskNo) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/apartment/sign-archive/tasks/" + taskNo)
                        .header("X-Auth-Token", token))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
    }

    private void awaitProgress(String token, String taskNo, String status, int progress, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode data;
        do {
            data = getTask(token, taskNo);
            if (status.equals(data.path("status").asText()) && data.path("progress").asInt() == progress) {
                return;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("任务未在 " + timeoutMs + "ms 内到达 " + status + "/" + progress + "%: " + data);
    }

    private JsonNode awaitStatus(String token, String taskNo, String status, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode data;
        do {
            data = getTask(token, taskNo);
            if (status.equals(data.path("status").asText())) {
                return data;
            }
            String s = data.path("status").asText();
            if ("SUCCESS".equals(status) && ("FAILED".equals(s) || "CANCELLED".equals(s))) {
                throw new AssertionError("等待 " + status + " 但任务进入 " + s + ": " + data);
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("任务 " + taskNo + " 在 " + timeoutMs + "ms 内未达到 " + status + ": " + data);
    }
}
