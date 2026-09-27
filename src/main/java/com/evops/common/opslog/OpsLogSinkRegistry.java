package com.evops.common.opslog;

/**
 * logback appender 与 Spring Sink 之间的静态桥：
 * logback 初始化早于 Spring，先通过注册表“挂空”；Spring 就绪后注册真正的异步入库 Sink，
 * 应用关闭时注销，避免日志打到已关闭的数据源。
 */
public final class OpsLogSinkRegistry {
    private static volatile OpsLogSink sink;

    private OpsLogSinkRegistry() {
    }

    public static void register(OpsLogSink s) {
        sink = s;
    }

    public static void clear() {
        sink = null;
    }

    static OpsLogSink get() {
        return sink;
    }
}
