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
 * 1. 请求开始<b>无条件清空 MDC</b>：容器线程池化复用，不把上一请求/线程池异步残留的
 *    traceId/合同号/账号带进本请求。清理不放在“靠上次请求的 finally 自觉执行”上；
 * 2. 入口生成 TraceId：入站带 {@code X-Trace-Id} 且合法（字母数字中划线、长度受限）则沿用，
 *    否则服务端生成，保证每次请求必有；
 * 3. 写入 SLF4J MDC，本请求内所有日志（含控制器/服务/异常处理器）固定携带 traceId，
 *    鉴权通过后由令牌过滤器补充 username；
 * 4. 响应头 {@code X-Trace-Id} <b>直接取 MDC 当前值</b>——与文件日志、采集库、响应体
 *    {@code traceId} 严格同源同值，不存在“头回带了、日志里查无此号”；
 * 5. 请求结束固定落一行 http-access 访问日志（方法/路径/状态/耗时），异常也不丢；
 * 6. finally 无条件清空 MDC，线程复用不串号（入口再清一次构成双保险）。
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
        // 无条件先清：线程复用时把任何残留上下文（traceId/contractNo/username）挡在本请求之外
        TraceContext.clear();
        if (properties.isEnabled()) {
            TraceContext.setTraceId(resolveInbound(request.getHeader(properties.getHeaderName())));
            // 响应头与日志同源：头的值就取 MDC 里日志正在用的那个 TraceId
            response.setHeader(properties.getHeaderName(), TraceContext.getTraceId());
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
                TraceContext.clear();
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
