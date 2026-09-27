package com.evops.common.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 全链路追踪入口过滤器（最外层，先于令牌鉴权过滤器执行）。
 *
 * 边界铁律（规矩定在系统里，不靠各调用方自觉清理）：
 * 1. <b>入口先消毒</b>：线程进入请求先清空 MDC，再生成/承接本请求 TraceId——
 *    池化线程上任何残留（非 /api/* 端点、应急关闭窗口、未来新加的绑定代码留下的）
 *    都不得带进本请求日志，链路号线程内唯一由边界保证；
 * 2. 写入 SLF4J MDC，本请求内所有日志（含控制器/服务/异常处理器）固定携带 traceId，
 *    鉴权通过后由令牌过滤器补充 username；
 * 3. <b>响应头与日志同源</b>：响应头 {@code X-Trace-Id} 从 MDC 回读写出，
 *    与日志格式、运维库采集读的是同一份事实，不存在两个副本漂移的可能；
 * 4. 请求结束固定落一行 http-access 访问日志（方法/路径/状态/耗时），异常也不丢；
 * 5. 出口清空 MDC 不被功能开关门控：业务代码绑定合同号不随开关，边界清理也不随开关。
 */
public class TraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("com.evops.access");

    private final TraceProperties properties;
    /** 入站值限宽字符集与长度，杜绝换行伪造日志行（日志注入）。 */
    private final Pattern safeInbound;

    public TraceFilter(TraceProperties properties) {
        this.properties = properties;
        this.safeInbound = Pattern.compile("[A-Za-z0-9-]{"
                + properties.getInboundMinLength() + "," + properties.getInboundMaxLength() + "}");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 入口消毒：无论线程此前经历过什么，本请求从干净的日志上下文开始
        TraceContext.clear();
        if (properties.isEnabled()) {
            String traceId = resolveInbound(request.getHeader(properties.getHeaderName()));
            TraceContext.setTraceId(traceId);
            // 响应头与日志同源：从 MDC 回读写出，不另存局部副本
            String current = TraceContext.getTraceId();
            if (current != null) {
                response.setHeader(properties.getHeaderName(), current);
            }
        } else {
            // 追踪关闭（应急回退）：不生成、不入 MDC；网关注入的合法值仍原样回带，排障链路不断
            String inbound = sanitizeInbound(request.getHeader(properties.getHeaderName()));
            if (inbound != null) {
                response.setHeader(properties.getHeaderName(), inbound);
            }
        }
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                if (properties.isEnabled()) {
                    log.info("http-access {} {} -> {} {}ms",
                            request.getMethod(), request.getRequestURI(),
                            response.getStatus(), System.currentTimeMillis() - start);
                }
            } finally {
                // 出口清理不随功能开关：服务绑定合同号不随开关，边界清理也不随开关
                TraceContext.clear();
            }
        }
    }

    /** 入站合法（限宽字符集与长度）则沿用，否则服务端生成，保证每次请求必有。 */
    private String resolveInbound(String inbound) {
        String v = sanitizeInbound(inbound);
        return v != null ? v : TraceContext.generate();
    }

    /** 入站值限宽字符集与长度，杜绝换行伪造日志行（日志注入）；不合法返回 null。 */
    private String sanitizeInbound(String inbound) {
        if (inbound != null) {
            String v = inbound.trim();
            if (v.length() >= properties.getInboundMinLength()
                    && v.length() <= properties.getInboundMaxLength()
                    && safeInbound.matcher(v).matches()) {
                return v;
            }
        }
        return null;
    }
}
