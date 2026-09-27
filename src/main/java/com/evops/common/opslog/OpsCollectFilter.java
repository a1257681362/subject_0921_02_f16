package com.evops.common.opslog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 入库采集范围（固定口径，不在配置中开放，避免把框架 DEBUG 噪音灌进运维库）：
 *
 * - 本系统日志（logger 前缀 com.evops）：INFO 及以上；
 * - 框架/容器日志（Spring/Tomcat/MyBatis 等）：WARN 及以上——故障排查常见的
 *   容器异常、连接池告警同样落库，可按时间窗聚合到一次请求附近。
 *
 * 总开关 {@code evops.ops-log.enabled} 经 {@link OpsDbAppender} 的静态标记生效，
 * 关闭后本过滤器全部 DENY（应急回退，文件日志不受影响）。
 */
public class OpsCollectFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!OpsDbAppender.isCollectEnabled()) {
            return FilterReply.DENY;
        }
        boolean ours = event.getLoggerName() != null && event.getLoggerName().startsWith("com.evops");
        int threshold = ours ? Level.INFO_INT : Level.WARN_INT;
        return event.getLevel().toInt() >= threshold ? FilterReply.ACCEPT : FilterReply.DENY;
    }
}
