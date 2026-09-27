package com.evops.apartment.opslog;

import com.evops.apartment.task.engine.TaskExecutor;
import com.evops.common.opslog.OpsDbAppender;
import com.evops.common.opslog.OpsLogEntry;
import com.evops.common.opslog.OpsLogEntryMapper;
import com.evops.common.opslog.OpsLogSinkService;
import com.evops.common.trace.MdcTaskDecorator;
import com.evops.common.trace.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-6 全链路 TraceId + 运维日志归集端到端：
 * 1. 入口生成/承接 TraceId，响应头与响应体回带，非法入站值不复用；
 * 2. 按合同号可聚合一次请求的全部日志（业务日志行带合同号与同一 TraceId）；
 * 3. 后台任务工作线程续传入站 TraceId 与合同号，20 步日志同号；
 * 4. 运维日志仅管家：主管/管理员 403、未登录 401；必带合同号/TraceId；
 *    SELF/SCOPE 范围口径，枚举他人 TraceId 被拒；
 * 5. 14 天滚动清理只删过期行；线程池装饰器跨线程传 MDC。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_apt6_test;DB_CLOSE_DELAY=-1",
        "evops.task.dispatch-interval-ms=100",
        "evops.task.dispatch-initial-delay-ms=100",
        "evops.esign.mock.step-delay-ms=0",
        "evops.esign.mock.sign-wait-ms=300",
        "evops.ops-log.flush-interval-ms=100"
})
@AutoConfigureMockMvc
class TraceAndOpsLogFlowTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OpsLogEntryMapper opsLogMapper;
    @Autowired private OpsLogSinkService opsLogSink;
    @Autowired private TaskExecutor taskExecutor;
    @Autowired private javax.sql.DataSource dataSource;

    private String stewardToken;
    private String supervisorToken;
    private String adminToken;
    private String areaToken;

    @BeforeEach
    void setUp() throws Exception {
        stewardToken = login("steward01", "steward123");
        supervisorToken = login("supervisor01", "supervisor123");
        adminToken = login("admin", "admin123");
        areaToken = login("area01", "area123");
    }

    @Test
    void entry_generatesTrace_echoHeaderAndBody_inboundRespectedOrReplaced() throws Exception {
        // 未登录 401 也带 TraceId（过滤器在鉴权之外）：响应头 + 响应体同值
        MvcResult denied = mockMvc.perform(get("/api/apartment/leases"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();
        String headerTrace = denied.getResponse().getHeader("X-Trace-Id");
        JsonNode body = objectMapper.readTree(denied.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(headerTrace, body.get("traceId").asText());

        // 合法入站 TraceId 原样承接并回带（网关/上游可贯通）
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", "inbound-trace-abc12345"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "inbound-trace-abc12345"))
                .andExpect(jsonPath("$.traceId").value("inbound-trace-abc12345"));

        // 非法入站值（含换行，疑似日志注入）不复用，服务端重新生成
        MvcResult replaced = mockMvc.perform(get("/api/apartment/leases")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", "abc\r\nFAKE LINE"))
                .andExpect(status().isOk())
                .andReturn();
        String actual = replaced.getResponse().getHeader("X-Trace-Id");
        assertNotNull(actual);
        assertFalse(actual.contains("\n"));
        assertNotEquals("abc\r\nFAKE LINE", actual);
    }

    @Test
    void contractNo_aggregatesAllLogsOfOneRequest_withSameTraceId() throws Exception {
        registerLedger(supervisorToken, "PROP-TRACE-001", "示例小区");
        String contractNo = "HT-TRACE-0001";

        MvcResult result = mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", "trace-contract-agg-0001")
                        .contentType("application/json")
                        .content(leaseJson(contractNo, "PROP-TRACE-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-contract-agg-0001"))
                .andReturn();

        // 采集异步入库：轮询等待业务日志行落库
        awaitLog("租约建档成功", contractNo, "trace-contract-agg-0001", 5000);

        // 管家按合同号检索：一次请求的日志聚齐，TraceId 一致，操作人可定位
        MvcResult query = mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", stewardToken)
                        .param("contractNo", contractNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode data = objectMapper.readTree(query.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
        assertTrue(data.get("count").asInt() >= 1, "按合同号应至少聚合到建档业务日志");
        for (JsonNode row : data.get("rows")) {
            assertEquals(contractNo, row.get("contractNo").asText());
            assertEquals("steward01", row.get("username").asText());
        }
        boolean hasBusinessLine = false;
        for (JsonNode row : data.get("rows")) {
            if (row.get("message").asText().contains("租约建档成功")) {
                hasBusinessLine = true;
                assertEquals("trace-contract-agg-0001", row.get("traceId").asText());
            }
        }
        assertTrue(hasBusinessLine, "应包含建档成功业务日志行");

        // 级别过滤：ERROR 过滤掉 INFO 业务行
        JsonNode errorsOnly = objectMapper.readTree(mockMvc.perform(
                get("/api/apartment/ops-log/entries").header("X-Auth-Token", stewardToken)
                        .param("contractNo", contractNo).param("level", "ERROR"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
        for (JsonNode row : errorsOnly.get("rows")) {
            assertEquals("ERROR", row.get("level").asText());
        }
    }

    @Test
    void backgroundTask_workersInheritTraceIdAndContract() throws Exception {
        registerLedger(supervisorToken, "PROP-TRACE-002", "示例小区");
        String contractNo = "HT-TRACE-BG-0001";
        String inboundTrace = "trace-background-pass-0002";

        // 发起请求带固定 TraceId：秒回任务详情即带同号
        MvcResult submit = mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", inboundTrace)
                        .contentType("application/json")
                        .content(submitJson(contractNo, "PROP-TRACE-002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(inboundTrace))
                .andReturn();
        String taskNo = objectMapper.readTree(submit.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data").get("taskNo").asText();
        awaitTaskSuccess(taskNo);

        // 后台工作线程日志续传：执行成功行带同一 TraceId 与合同号
        awaitLog("执行成功 100%", contractNo, inboundTrace, 5000);
    }

    @Test
    void opsLog_stewardsOnly_requiredParams_andScopeGuarded() throws Exception {
        // 未登录 401
        mockMvc.perform(get("/api/apartment/ops-log/entries").param("contractNo", "X"))
                .andExpect(status().isUnauthorized());
        // 主管/管理员 403（入口只放给管家；combo 兼管家故有权限，符合并集口径）
        mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", supervisorToken).param("contractNo", "X"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", adminToken).param("contractNo", "X"))
                .andExpect(status().isForbidden());

        // 管家：必带合同号或 TraceId
        mockMvc.perform(get("/api/apartment/ops-log/entries").header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        // 枚举不属于本人的 TraceId：403（防遍历他人请求）
        mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", stewardToken)
                        .param("traceId", "never-existed-trace-zzzz"))
                .andExpect(status().isForbidden());

        // SCOPE：其他小区的合同 403（先由 ALL 角色在其他小区建档）
        registerLedger(supervisorToken, "PROP-TRACE-OTHER", "其他小区");
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", adminToken)
                        .header("X-Trace-Id", "trace-admin-other-community")
                        .contentType("application/json")
                        .content(leaseJson("HT-TRACE-OTHER-0001", "PROP-TRACE-OTHER")))
                .andExpect(status().isOk());
        awaitLog("租约建档成功", "HT-TRACE-OTHER-0001", "trace-admin-other-community", 5000);
        mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", areaToken)
                        .param("contractNo", "HT-TRACE-OTHER-0001"))
                .andExpect(status().isForbidden());
        // SELF：别人经办的合同 403
        mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", stewardToken)
                        .param("contractNo", "HT-TRACE-OTHER-0001"))
                .andExpect(status().isForbidden());
    }

    @Test
    void retentionCleanup_deletesOnlyRowsOlderThan14Days() {
        OpsLogEntry fresh = new OpsLogEntry();
        fresh.setLogTime(LocalDateTime.now().minusHours(1));
        fresh.setLevel("INFO");
        fresh.setLoggerName("test.Logger");
        fresh.setMessage("fresh-row");
        opsLogMapper.insert(fresh);

        OpsLogEntry old = new OpsLogEntry();
        old.setLogTime(LocalDateTime.now().minusDays(15));
        old.setLevel("INFO");
        old.setLoggerName("test.Logger");
        old.setMessage("old-row");
        opsLogMapper.insert(old);

        opsLogSink.cleanup();

        assertEquals(0L, opsLogMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                .eq("message", "old-row")).longValue());
        assertEquals(1L, opsLogMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                .eq("message", "fresh-row")).longValue());
    }

    @Test
    void mdcTaskDecorator_propagatesAcrossThreads_andRestores() throws Exception {
        TraceContext.setTraceId("decorator-trace-0001");
        TraceContext.bindContract("HT-DECORATOR-1");
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenContract = new AtomicReference<>();
        Runnable work = MdcTaskDecorator.wrap(() -> {
            seenTrace.set(MDC.get(TraceContext.TRACE_ID));
            seenContract.set(MDC.get(TraceContext.CONTRACT_NO));
        });
        Thread t = new Thread(work);
        t.start();
        t.join(2000);
        assertEquals("decorator-trace-0001", seenTrace.get());
        assertEquals("HT-DECORATOR-1", seenContract.get());
        TraceContext.clear();
        // 采集丢弃计数可读取且不抛异常（队列满/关闭期间的可观测口径）
        assertTrue(OpsDbAppender.getDroppedCount() >= 0);
    }

    @Test
    void schemaRerun_adminAndSupervisorStillCannotReacquireOpsPermission() throws Exception {
        // 旧文件库每次启动都重跑 schema.sql（mode=always）：apt-3 段 admin CROSS JOIN 全部权限点、
        // supervisor CROSS JOIN apartment 模块的 MERGE 会在第二轮把新增 25-26 授出去。
        // 手工再执行一遍初始化脚本模拟“二次启动”，收权语句必须仍然挡住。
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                dataSource.getConnection(),
                new org.springframework.core.io.ClassPathResource("schema.sql"));

        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buttons",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("apartment:opslog:view"))));
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buttons",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("apartment:opslog:view"))));
        // 管家侧不受收权影响（combo 兼管家也仍有权限）
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", stewardToken))
                .andExpect(jsonPath("$.data.buttons",
                        org.hamcrest.Matchers.hasItem("apartment:opslog:view")));
        mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", adminToken).param("contractNo", "X"))
                .andExpect(status().isForbidden());
    }

    // ---------------- helpers ----------------

    private void awaitLog(String messagePart, String contractNo, String traceId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Long n = opsLogMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                    .eq("contract_no", contractNo)
                    .eq("trace_id", traceId)
                    .like("message", messagePart));
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待日志落库超时: " + messagePart + " contractNo=" + contractNo
                + " traceId=" + traceId);
    }

    private void awaitTaskSuccess(String taskNo) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = mockMvc.perform(get("/api/apartment/sign-archive/tasks/" + taskNo)
                            .header("X-Auth-Token", stewardToken))
                    .andExpect(status().isOk()).andReturn();
            JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
            String status = data.get("status").asText();
            if ("SUCCESS".equals(status)) {
                assertEquals(100, data.get("progress").asInt());
                return;
            }
            if ("FAILED".equals(status)) {
                throw new AssertionError("任务意外失败: " + data.get("failReason").asText());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待任务 SUCCESS 超时: " + taskNo);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        return node.get("data").get("token").asText();
    }

    private void registerLedger(String token, String code, String community) throws Exception {
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("{\"propertyCode\":\"" + code + "\",\"community\":\"" + community + "\","
                                + "\"building\":\"1栋\",\"roomNo\":\"101\",\"area\":45.5,"
                                + "\"requestNo\":\"REQ-L-" + code + "\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk());
    }

    private String leaseJson(String contractNo, String propertyCode) {
        return "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"" + propertyCode + "\","
                + "\"tenantName\":\"张三\",\"tenantPhone\":\"13800000000\","
                + "\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                + "\"monthlyRent\":3000,\"deposit\":1000,"
                + "\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"ignored\"}";
    }

    private String submitJson(String contractNo, String propertyCode) {
        return "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"" + propertyCode + "\","
                + "\"tenantName\":\"张三\",\"tenantPhone\":\"13800000000\","
                + "\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                + "\"monthlyRent\":3000,\"deposit\":1000,"
                + "\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"ignored\"}";
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        assertFalse(java.util.Objects.equals(unexpected, actual));
    }
}
