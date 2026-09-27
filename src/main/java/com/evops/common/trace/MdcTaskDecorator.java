package com.evops.common.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 线程池 MDC 传递装饰器：任务提交线程的 MDC（traceId/contractNo/username）随任务带到执行线程，
 * 执行结束恢复执行线程原 MDC，池化线程复用时不串号。
 *
 * 后台任务执行不直接依赖本装饰器——任务在调度线程被投递、提交线程没有任务的 TraceId，
 * 执行器在工作线程内按任务表存的 traceId 重新绑定；本装饰器保证任何
 * “请求线程内 @Async/线程池异步执行” 的日志仍归属原请求。
 */
public class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        TraceContext.MdcSnapshot snapshot = TraceContext.snapshot();
        return () -> {
            TraceContext.MdcSnapshot previous = TraceContext.apply(snapshot);
            try {
                runnable.run();
            } finally {
                TraceContext.apply(previous);
            }
        };
    }

    /** 供非 Spring TaskDecorator 的线程池手工包装使用。 */
    public static Runnable wrap(Runnable runnable) {
        return new MdcTaskDecorator().decorate(runnable);
    }

    /** 当前 MDC 是否为空（测试与执行器续传时用）。 */
    public static boolean empty() {
        return MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty();
    }
}
