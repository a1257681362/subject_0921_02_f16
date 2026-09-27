package com.evops.apartment.opslog;

import com.evops.common.opslog.OpsLogEntry;
import com.evops.common.opslog.OpsLogEntryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 串号复现（核实“按合同号聚合会混进别人的单号”是否属实）：
 *
 * 模拟生产上 Tomcat/工作池线程复用——MockMvc 与任务执行都在调用线程上跑，
 * 同一测试线程顺序承载多个请求，等价于线上同一 http-nio 线程串行处理多个请求：
 *
 * 1. 请求 A 建档合同 X（成功）→ 请求 B 同合同重复建档（异常分支，唯一约束冲突）
 *    → 请求 C 纯查询（全程不绑合同）→ 请求 D 建档合同 Y；
 *    断言：C 的链路下不允许出现任何合同号；D 的链路下不允许出现 X；
 *    凡带合同号的行，其 traceId 必须是该合同自己请求的 traceId。
 * 2. 后台任务工作线程复用：合同 P 的归档任务跑完后，同一工作线程再跑合同 Q 的任务；
 *    断言：Q 的日志不得挂在 P 的链路号/合同号下，反之亦然。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_trace_isolation_test;DB_CLOSE_DELAY=-1",
        "evops.task.dispatch-interval-ms=100",
        "evops.task.dispatch-initial-delay-ms=100",
        "evops.esign.mock.step-delay-ms=0",
        "evops.esign.mock.sign-wait-ms=300",
        "evops.ops-log.flush-interval-ms=100"
})
@AutoConfigureMockMvc
class TraceContextIsolationReproTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OpsLogEntryMapper opsLogMapper;

    private String stewardToken;

    @BeforeEach
    void setUp() throws Exception {
        stewardToken = login("steward01", "steward123");
    }

    @Test
    void requestThreadReuse_neverCarriesForeignContract() throws Exception {
        registerLedger("PROP-ISO-001", "隔离小区");
        String contractX = "HT-ISO-X-0001";
        String contractY = "HT-ISO-Y-0001";

        // A：合同 X 建档成功
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", "trace-iso-a-00000001")
                        .contentType("application/json")
                        .content(leaseJson(contractX, "PROP-ISO-001")))
                .andExpect(status().isOk());
        // B：同合同重复建档 → 唯一约束冲突，走异常分支（绑定合同后抛出）
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", "trace-iso-b-00000001")
                        .contentType("application/json")
                        .content(leaseJson(contractX, "PROP-ISO-001")))
                .andExpect(status().isOk());
        // C：纯查询请求，全程不绑定合同号
        mockMvc.perform(get("/api/apartment/leases")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", "trace-iso-c-00000001")
                        .param("keyword", "张"))
                .andExpect(status().isOk());
        // D：合同 Y 建档成功
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", "trace-iso-d-00000001")
                        .contentType("application/json")
                        .content(leaseJson(contractY, "PROP-ISO-001")))
                .andExpect(status().isOk());

        awaitLog("租约建档成功", contractX, "trace-iso-a-00000001", 5000);
        awaitLog("租约建档成功", contractY, "trace-iso-d-00000001", 5000);
        Thread.sleep(500); // 等 C 的访问日志落库

        // C 的链路：不允许出现任何合同号（残留即串号）
        List<OpsLogEntry> cRows = selectByTrace("trace-iso-c-00000001");
        assertTrue(cRows.stream().allMatch(r -> r.getContractNo() == null),
                "无合同上下文的请求链路混入合同号: " + summarize(cRows));

        // D 的链路：只允许合同 Y，绝不允许出现 X
        List<OpsLogEntry> dRows = selectByTrace("trace-iso-d-00000001");
        assertTrue(dRows.stream().allMatch(r -> r.getContractNo() == null || contractY.equals(r.getContractNo())),
                "合同 Y 的请求链路混入合同 X: " + summarize(dRows));

        // 反向：凡带合同号的行，traceId 必须是该合同自己的请求
        List<OpsLogEntry> xRows = selectByContract(contractX);
        assertTrue(xRows.stream().allMatch(r -> r.getTraceId().startsWith("trace-iso-a")
                        || r.getTraceId().startsWith("trace-iso-b")),
                "合同 X 的日志挂到了别人的链路号下: " + summarize(xRows));
        List<OpsLogEntry> yRows = selectByContract(contractY);
        assertTrue(yRows.stream().allMatch(r -> r.getTraceId().startsWith("trace-iso-d")),
                "合同 Y 的日志挂到了别人的链路号下: " + summarize(yRows));
    }

    @Test
    void workerThreadReuse_tasksOfDifferentContractsStayIsolated() throws Exception {
        registerLedger("PROP-ISO-002", "隔离小区");
        String contractP = "HT-ISO-P-0001";
        String contractQ = "HT-ISO-Q-0001";

        String taskP = submitAndGetTaskNo(contractP, "PROP-ISO-002", "trace-iso-p-00000001");
        awaitTaskSuccess(taskP);
        String taskQ = submitAndGetTaskNo(contractQ, "PROP-ISO-002", "trace-iso-q-00000001");
        awaitTaskSuccess(taskQ);

        awaitLog("执行成功 100%", contractP, "trace-iso-p-00000001", 8000);
        awaitLog("执行成功 100%", contractQ, "trace-iso-q-00000001", 8000);

        List<OpsLogEntry> pRows = selectByContract(contractP);
        assertTrue(pRows.stream().allMatch(r -> "trace-iso-p-00000001".equals(r.getTraceId())),
                "合同 P 的后台日志挂到别的链路号: " + summarize(pRows));
        List<OpsLogEntry> qRows = selectByContract(contractQ);
        assertTrue(qRows.stream().allMatch(r -> "trace-iso-q-00000001".equals(r.getTraceId())),
                "合同 Q 的后台日志挂到别的链路号: " + summarize(qRows));
        // P 的链路里绝不允许出现 Q 的合同号（工作线程残留）
        List<OpsLogEntry> pTraceRows = selectByTrace("trace-iso-p-00000001");
        assertTrue(pTraceRows.stream().allMatch(r -> r.getContractNo() == null || contractP.equals(r.getContractNo())),
                "合同 P 的链路混入合同 Q: " + summarize(pTraceRows));
    }

    // ---------------- helpers ----------------

    private List<OpsLogEntry> selectByTrace(String traceId) {
        return opsLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                .eq("trace_id", traceId));
    }

    private List<OpsLogEntry> selectByContract(String contractNo) {
        return opsLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                .eq("contract_no", contractNo));
    }

    private String summarize(List<OpsLogEntry> rows) {
        StringBuilder sb = new StringBuilder();
        for (OpsLogEntry r : rows) {
            sb.append("\n  trace=").append(r.getTraceId())
                    .append(" contract=").append(r.getContractNo())
                    .append(" logger=").append(r.getLoggerName())
                    .append(" msg=").append(r.getMessage() == null ? ""
                            : r.getMessage().substring(0, Math.min(60, r.getMessage().length())));
        }
        return sb.toString();
    }

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
        throw new AssertionError("等待日志落库超时: " + messagePart);
    }

    private void awaitTaskSuccess(String taskNo) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = mockMvc.perform(get("/api/apartment/sign-archive/tasks/" + taskNo)
                            .header("X-Auth-Token", stewardToken))
                    .andExpect(status().isOk()).andReturn();
            JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString(
                    java.nio.charset.StandardCharsets.UTF_8)).get("data");
            String status = data.get("status").asText();
            if ("SUCCESS".equals(status)) {
                return;
            }
            if ("FAILED".equals(status)) {
                throw new AssertionError("任务意外失败: " + data.get("failReason").asText());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待任务 SUCCESS 超时: " + taskNo);
    }

    private String submitAndGetTaskNo(String contractNo, String propertyCode, String traceId) throws Exception {
        MvcResult submit = mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken)
                        .header("X-Trace-Id", traceId)
                        .contentType("application/json")
                        .content(leaseJson(contractNo, propertyCode)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(submit.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8)).get("data").get("taskNo").asText();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8));
        return node.get("data").get("token").asText();
    }

    private void registerLedger(String code, String community) throws Exception {
        String supervisorToken = login("supervisor01", "supervisor123");
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", supervisorToken)
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
}
