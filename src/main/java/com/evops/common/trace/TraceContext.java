package com.evops.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路 TraceId 的线程上下文（基于 SLF4J MDC）：
 *
 * - 请求入口过滤器在请求开始时无条件清空 MDC 再生成/承接 TraceId，请求结束无条件清空，
 *   合同号/账号的生命周期一律由入口边界托管，业务代码只绑定、不负责清理，杜绝“靠自觉”；
 * - 后台任务执行器在工作线程认领任务时先清空再按任务表重绑，结束无条件清空，
 *   同一合同从发起到后台每一步的全部日志共享一个 TraceId，池化线程复用不带上一单的号；
 * - 合同号作为业务定位由业务入口绑定（绑定为权威写：空值即摘除），日志检索可按合同号
 *   聚合一次请求的全部日志。
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

    /**
     * 权威写入 TraceId：空值（null/空串）即摘除本线程 TraceId，而不是静默保留旧值。
     * 池化线程重绑时必须先清空或显式摘除，避免上一任务/上一请求的号残留。
     */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(TRACE_ID, traceId);
        } else {
            MDC.remove(TRACE_ID);
        }
    }

    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 权威绑定合同号到当前线程日志上下文（请求线程与任务工作线程共用）：
     * 空值（null/空串）即摘除，绝不静默沿用上一个合同号——
     * “绑了空值却残留上一单合同号”是按合同聚合日志串号的直接来源。
     */
    public static void bindContract(String contractNo) {
        if (contractNo != null && !contractNo.isEmpty()) {
            MDC.put(CONTRACT_NO, contractNo);
        } else {
            MDC.remove(CONTRACT_NO);
        }
    }

    public static void clearContract() {
        MDC.remove(CONTRACT_NO);
    }

    /** 权威写入账号：空值即摘除，避免池化线程复用时残留上一任务的操作人。 */
    public static void setUsername(String username) {
        if (username != null && !username.isEmpty()) {
            MDC.put(USERNAME, username);
        } else {
            MDC.remove(USERNAME);
        }
    }

    /**
     * 把合同号绑入当前线程并返回一个<b>必须关闭</b>的作用域（推荐 try-with-resources）：
     * 关闭时自动恢复绑定前的值，业务代码不再各自手写“暂存—finally 恢复”，
     * 规矩由本类强制，不依赖开发者记得配对清理。
     */
    public static Scope contractScope(String contractNo) {
        String previous = MDC.get(CONTRACT_NO);
        bindContract(contractNo);
        return () -> bindContract(previous);
    }

    /** MDC 键作用域：关闭即恢复进入前的单键值。 */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
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
