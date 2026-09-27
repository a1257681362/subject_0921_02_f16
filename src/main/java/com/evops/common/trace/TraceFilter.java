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
 * 全链路追踪入口过滤器（最外层，先于令牌鉴权过滤器执行）：
 *
 * 1. 入口生成 TraceId：入站带 {@code X-Trace-Id} 且合法（字母数字中划线、长度受限）则沿用，
 *    否则服务端生成，保证每次请求必有；
 * 2. 写入 SLF4J MDC，本请求内所有日志（含控制器/服务/异常处理器）固定携带 traceId，
 *    鉴权通过后由令牌过滤器补充 username；
 * 3. 响应头 {@code X-Trace-Id} 原样回带，调用方报错凭此头一次聚合同一请求的全部日志；
 * 4. 请求结束固定落一行 http-access 访问日志（方法/路径/状态/耗时），异常也不丢；
 * 5. finally 清空 MDC，线程复用不串号。
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
        String traceId = null;
        if (properties.isEnabled()) {
            traceId = resolveInbound(request.getHeader(properties.getHeaderName()));
            TraceContext.setTraceId(traceId);
        }
        // 即使追踪关闭也把网关注入的合法值回带，保持排障链路不断
        if (traceId != null) {
            response.setHeader(properties.getHeaderName(), traceId);
        }
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (properties.isEnabled()) {
                try {
                    log.info("http-access {} {} -> {} {}ms",
                            request.getMethod(), request.getRequestURI(),
                            response.getStatus(), System.currentTimeMillis() - start);
                } finally {
                    TraceContext.clear();
                }
            }
        }
    }

    /** 入站值限宽字符集与长度，杜绝换行伪造日志行（日志注入）。 */
    private String resolveInbound(String inbound) {
        if (inbound != null) {
            String v = inbound.trim();
            if (v.length() >= properties.getInboundMinLength()
                    && v.length() <= properties.getInboundMaxLength()
                    && safeInbound.matcher(v).matches()) {
                return v;
            }
        }
        return TraceContext.generate();
    }
}
