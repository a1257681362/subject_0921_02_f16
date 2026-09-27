package com.evops.apartment.opslog;

import com.evops.common.opslog.OpsLogEntry;
import com.evops.common.opslog.OpsLogEntryMapper;
import com.evops.common.trace.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 边界不变量（把规矩定进系统，不靠每个调用方自觉清理）：
 *
 * 线程一旦带着外来日志上下文进入请求（任何未覆盖路径留下的残留——非 /api/* 端点、
 * 应急关闭 trace 的窗口、未来新加的绑定代码），入口过滤器必须在边界上把它拦下：
 * 本请求的日志（含入库的 http-access 行）只允许带本请求自己的上下文。
 *
 * 修复前：TraceFilter 只在出口清理且被 enabled 开关门控，入口不消毒——
 * 残留合同号会原样穿过整个请求，http-access 行带着别人单号入运维库，
 * 管家按该单号聚合时即看到本次无关请求的日志（串号）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_trace_boundary_test;DB_CLOSE_DELAY=-1",
        "evops.task.dispatch-interval-ms=100",
        "evops.task.dispatch-initial-delay-ms=100",
        "evops.ops-log.flush-interval-ms=100"
})
@AutoConfigureMockMvc
class TraceBoundaryInvariantTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OpsLogEntryMapper opsLogMapper;

    @AfterEach
    void cleanUp() {
        TraceContext.clear();
    }

    @Test
    void entrySanitizesForeignContext_accessLogNeverFilesUnderForeignContract() throws Exception {
        String stewardToken = login("steward01", "steward123");

        // 模拟“某条未覆盖路径”留在线程上的残留：外来合同号 + 外来链路号
        MDC.put(TraceContext.TRACE_ID, "trace-foreign-residue-01");
        MDC.put(TraceContext.CONTRACT_NO, "HT-FOREIGN-LEAK-0001");

        // 本请求自身不绑定任何合同（纯查询），只应携带自己的链路号
        MvcResult result = mockMvc.perform(get("/api/apartment/leases")
                        .header("X-Auth-Token", stewardToken)
                        .param("keyword", "张"))
                .andExpect(status().isOk())
                .andReturn();
        String ownTrace = result.getResponse().getHeader("X-Trace-Id");

        // 响应头必须就是本请求日志用的链路号（同源），且绝不是残留值
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(ownTrace, body.get("traceId").asText(), "响应头与响应体链路号必须同源");
        assertTrue(!"trace-foreign-residue-01".equals(ownTrace), "链路号不得沿用线程残留值");

        // 等 http-access 行落库后核验：本请求的入库日志不得挂在外来合同号下
        awaitTracePersisted(ownTrace, 5000);
        List<OpsLogEntry> rows = opsLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                        .eq("trace_id", ownTrace));
        assertTrue(!rows.isEmpty(), "本请求应至少有一行入库日志（http-access）");
        for (OpsLogEntry row : rows) {
            assertTrue(row.getContractNo() == null,
                    "本请求日志混入外来合同号: contractNo=" + row.getContractNo()
                            + " logger=" + row.getLoggerName() + " msg=" + row.getMessage());
        }

        // 反向核验：外来合同号名下不得出现本请求的日志行
        Long foreignRows = opsLogMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                        .eq("contract_no", "HT-FOREIGN-LEAK-0001"));
        assertEquals(0L, foreignRows.longValue(),
                "外来合同号名下不得聚合到本次无关请求的日志");
    }

    // ---------------- helpers ----------------

    private void awaitTracePersisted(String traceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Long n = opsLogMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpsLogEntry>()
                            .eq("trace_id", traceId));
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待日志落库超时: " + traceId);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .contentType("application/json")
                                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8));
        return node.get("data").get("token").asText();
    }
}
