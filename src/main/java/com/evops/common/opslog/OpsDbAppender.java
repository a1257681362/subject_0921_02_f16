package com.evops.common.opslog;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 采集 appender：logback 同步调用 {@link #append}，这里只做固定字段快照并交给
 * {@link OpsLogSinkRegistry}（Spring 启动后注册的异步入库 Sink）。
 *
 * 设计要点：
 * - 不持有 Spring 依赖：logback 初始化早于 Spring 上下文，Sink 经静态注册表解耦；
 * - 不阻塞业务线程：注册表在 Spring 就绪前/关闭后、或有界队列满时丢弃记录并计数，
 *   绝不向日志调用方抛异常（日志故障不能拖垮业务）；
 * - 采集开关为静态标记，由 {@code OpsLogProperties} 在应用启动时置位，默认开。
 */
public class OpsDbAppender extends AppenderBase<ILoggingEvent> {
    private static volatile boolean collectEnabled = false;
    private static volatile long droppedCount = 0;

    static void setCollectEnabled(boolean enabled) {
        collectEnabled = enabled;
    }

    public static boolean isCollectEnabled() {
        return collectEnabled;
    }

    /** 队列满/无 Sink 时累计的丢弃条数，暴露给运维页面与健康检查口径。 */
    public static long getDroppedCount() {
        return droppedCount;
    }

    @Override
    protected void append(ILoggingEvent event) {
        OpsLogSink sink = OpsLogSinkRegistry.get();
        if (sink == null) {
            droppedCount++;
            return;
        }
        try {
            // getMDCPropertyMap 在异步线程外读取（事件已携带快照），不依赖当前线程 MDC
            Map<String, String> mdc = event.getMDCPropertyMap();
            String stack = null;
            if (event.getThrowableProxy() instanceof ThrowableProxy) {
                Throwable t = ((ThrowableProxy) event.getThrowableProxy()).getThrowable();
                stack = OpsLogRecords.stackToString(t);
            }
            OpsLogRecord record = new OpsLogRecord(
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(event.getTimeStamp()),
                            ZoneId.systemDefault()),
                    event.getLevel().toString(),
                    truncate(event.getLoggerName(), 200),
                    truncate(event.getThreadName(), 64),
                    truncate(mdc.get("traceId"), 64),
                    truncate(mdc.get("contractNo"), 64),
                    truncate(mdc.get("username"), 64),
                    truncate(event.getFormattedMessage(), 2000),
                    truncate(stack, 4000));
            if (!sink.offer(record)) {
                droppedCount++;
            }
        } catch (Throwable t) {
            // 采集本身任何异常都不得影响业务与主日志
            droppedCount++;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
