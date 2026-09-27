package com.evops.common.opslog;

/**
 * 异步入库 Sink 的最小契约：{@link OpsDbAppender}（logback 侧，非 Spring 管理）
 * 通过 {@link OpsLogSinkRegistry} 拿到 Spring 启动后注册的实现。
 */
public interface OpsLogSink {
    /**
     * 投递一条采集记录。返回 false 表示有界队列已满、记录被丢弃（由 appender 计数）。
     * 实现必须保证不抛出异常。
     */
    boolean offer(OpsLogRecord record);
}
