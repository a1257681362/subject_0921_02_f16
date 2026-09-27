package com.evops.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 链路上下文边界回归（apt-15）：
 *
 * 规矩必须定在系统里而不是靠各业务方法自觉：
 * 1. 绑定为权威写——空值即摘除，池化线程重绑时空合同号/空账号不得残留上一单的值；
 * 2. contractScope 用 try-with-resources 强制收口，异常路径也还原；
 * 3. TraceFilter 入口无条件清空——线程复用时上一请求残留的 traceId/合同号/账号不得带进本请求；
 * 4. 响应头 X-Trace-Id 直接取 MDC——与文件日志/采集库/响应体同源同值。
 */
class TraceContextBoundaryTest {

    @AfterEach
    void cleanMdc() {
        TraceContext.clear();
    }

    @Test
    void bindNull_clearsStaleValue_doesNotKeepPreviousContract() {
        // 复现缺陷根因：先绑 A 单，再用空值“绑定”B 单（任务表 biz_ref/operator 缺失）
        TraceContext.setTraceId("trace-A");
        TraceContext.bindContract("HT-A-0001");
        TraceContext.setUsername("stewardA");

        TraceContext.setTraceId("trace-B");
        TraceContext.bindContract(null);
        TraceContext.setUsername("");

        assertEquals("trace-B", TraceContext.getTraceId());
        assertNull(MDC.get(TraceContext.CONTRACT_NO), "空合同号必须摘除旧值，否则 B 单日志挂到 A 单名下");
        assertNull(MDC.get(TraceContext.USERNAME), "空账号必须摘除旧值");
    }

    @Test
    void contractScope_restoresPrevious_evenOnException() {
        TraceContext.bindContract("HT-OUTER-1");
        IllegalStateException boom = assertThrows(IllegalStateException.class, () -> {
            try (TraceContext.Scope ignored = TraceContext.contractScope("HT-INNER-1")) {
                assertEquals("HT-INNER-1", MDC.get(TraceContext.CONTRACT_NO));
                throw new IllegalStateException("业务异常");
            }
        });
        assertEquals("业务异常", boom.getMessage());
        assertEquals("HT-OUTER-1", MDC.get(TraceContext.CONTRACT_NO), "异常退出作用域也必须还原");

        // 进入前无合同号：作用域结束后必须是“无”，而不是残留作用域内的号
        TraceContext.clearContract();
        try (TraceContext.Scope ignored = TraceContext.contractScope("HT-INNER-2")) {
            assertEquals("HT-INNER-2", MDC.get(TraceContext.CONTRACT_NO));
        }
        assertNull(MDC.get(TraceContext.CONTRACT_NO));
    }

    @Test
    void traceFilter_wipesStaleThreadMdc_andHeaderEqualsLoggedMdcValue() throws Exception {
        TraceProperties properties = new TraceProperties();
        TraceFilter filter = new TraceFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/apartment/leases");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 模拟池化请求线程上残留着“上一单”的完整上下文（旧实现只能指望上次请求 finally 自觉清）
        TraceContext.setTraceId("stale-trace-from-prev-request");
        TraceContext.bindContract("HT-STALE-PREV");
        TraceContext.setUsername("prevUser");

        AtomicReference<String> insideTrace = new AtomicReference<>();
        AtomicReference<String> insideContract = new AtomicReference<>();
        AtomicReference<String> insideUser = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res)
                    throws java.io.IOException, javax.servlet.ServletException {
                insideTrace.set(TraceContext.getTraceId());
                insideContract.set(MDC.get(TraceContext.CONTRACT_NO));
                insideUser.set(MDC.get(TraceContext.USERNAME));
                super.doFilter(req, res);
            }
        };

        filter.doFilter(request, response, chain);

        assertFalse("stale-trace-from-prev-request".equals(insideTrace.get()),
                "上一请求的 TraceId 不得残留到本请求");
        assertNull(insideContract.get(), "上一请求的合同号不得残留到本请求");
        assertNull(insideUser.get(), "上一请求的账号不得残留到本请求");
        // 响应头与日志同源：头的值必须是 MDC 里日志正在用的值
        assertEquals(insideTrace.get(), response.getHeader("X-Trace-Id"));
        // 请求结束 MDC 清空，线程归还后无任何残留
        assertNull(MDC.get(TraceContext.TRACE_ID));
        assertNull(MDC.get(TraceContext.CONTRACT_NO));
        assertNull(MDC.get(TraceContext.USERNAME));
    }

    @Test
    void traceFilter_respectsInboundTraceId_headerBodyAndLogsShareIt() throws Exception {
        TraceFilter filter = new TraceFilter(new TraceProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/apartment/leases");
        request.addHeader("X-Trace-Id", "inbound-trace-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideTrace = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res)
                    throws java.io.IOException, javax.servlet.ServletException {
                insideTrace.set(TraceContext.getTraceId());
                super.doFilter(req, res);
            }
        };

        filter.doFilter(request, response, chain);

        assertEquals("inbound-trace-0001", insideTrace.get());
        assertEquals("inbound-trace-0001", response.getHeader("X-Trace-Id"));
    }

    @Test
    void traceFilter_disabled_setsNoHeader_andLeavesNoTraceInMdc() throws Exception {
        // 响应头必须与日志同源：关闭追踪时日志无 traceId，就不能再回带头让管家凭头查空
        TraceProperties properties = new TraceProperties();
        properties.setEnabled(false);
        TraceFilter filter = new TraceFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/apartment/leases");
        request.addHeader("X-Trace-Id", "gateway-injected-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideTrace = new AtomicReference<>("init");
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res)
                    throws java.io.IOException, javax.servlet.ServletException {
                insideTrace.set(TraceContext.getTraceId());
                super.doFilter(req, res);
            }
        };

        filter.doFilter(request, response, chain);

        assertNull(insideTrace.get());
        assertNull(response.getHeader("X-Trace-Id"), "追踪关闭时不得回带头，避免头与日志不同源");
    }
}
