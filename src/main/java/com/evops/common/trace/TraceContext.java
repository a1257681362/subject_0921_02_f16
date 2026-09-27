package com.evops.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路 TraceId 的线程上下文（基于 SLF4J MDC）：
 *
 * - 请求入口过滤器在请求开始时生成/承接 TraceId 并写入 MDC，请求结束清理；
 * - 后台任务执行前把发起请求的 TraceId 重新绑定到工作线程，结束后清理，
 *   同一合同从发起到后台每一步的全部日志共享一个 TraceId；
 * - 合同号作为业务定位一并入 MDC，日志检索可按合同号聚合一次请求的全部日志。
 *
 * 仅承载日志上下文，不改变任何业务语义；日志格式（logback-spring.xml）固定输出这些键。
 */
public final class TraceContext {
    /** 全链路追踪号，入口生成，响应头 X-Trace-Id 原样回带。 */
    public static final String TRACE_ID = "traceId";
    /** 业务定位：合同号（有合同上下文的请求/任务），用于按合同聚合日志。 */
    public static final String CONTRACT_NO = "contractNo";
    /** 当前账号：鉴权过滤器解析身份后写入。 */
    public static final String USERNAME = "username";

    private TraceContext() {
    }

    /** 入口生成新 TraceId：时间戳可读前缀 + 12 位随机，长度固定 32。 */
    public static String generate() {
        String raw = UUID.randomUUID().toString().replace("-", "");
        return Long.toHexString(System.currentTimeMillis()) + raw.substring(0, 12);
    }

    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    /** 绑定合同号到当前线程日志上下文（请求线程与任务工作线程共用）。 */
    public static void bindContract(String contractNo) {
        if (contractNo != null && !contractNo.isEmpty()) {
            MDC.put(CONTRACT_NO, contractNo);
        }
    }

    public static void clearContract() {
        MDC.remove(CONTRACT_NO);
    }

    public static void setUsername(String username) {
        if (username != null && !username.isEmpty()) {
            MDC.put(USERNAME, username);
        }
    }

    /** 快照当前 MDC：供跨线程池传递后原样恢复。 */
    public static MdcSnapshot snapshot() {
        return new MdcSnapshot(MDC.getCopyOfContextMap());
    }

    /** 用快照覆盖当前线程 MDC（null 快照表示无上下文），返回恢复前快照。 */
    public static MdcSnapshot apply(MdcSnapshot snapshot) {
        MdcSnapshot previous = snapshot();
        if (snapshot == null || snapshot.map == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot.map);
        }
        return previous;
    }

    public static void clear() {
        MDC.clear();
    }

    /** MDC 不可变快照，跨线程传递。 */
    public static final class MdcSnapshot {
        private final java.util.Map<String, String> map;

        private MdcSnapshot(java.util.Map<String, String> map) {
            this.map = map == null ? null : new java.util.HashMap<>(map);
        }
    }
}
