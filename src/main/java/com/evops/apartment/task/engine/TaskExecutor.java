package com.evops.apartment.task.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.task.TaskProperties;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import com.evops.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 单个后台任务的执行循环：
 *
 * 1. CAS 认领 PENDING → RUNNING；领不到说明已被其他执行者拿走，直接退出；
 * 2. 每个一致步骤执行前重新读取取消标记：置位则停在“下个一致步骤”边界——
 *    当前步骤已提交完，进度停在 5 的整数倍，任务 CANCELLED；
 * 3. 步骤返回 WAIT（等租客签署）：CAS 退回 PENDING + next_run_at，释放执行槽，到点重领；
 *    若等待期间已置取消标记（取消与退回并发），退回失败，就地落 CANCELLED 停在当前一致边界；
 * 4. 步骤抛异常：FAILED + fail_step/fail_reason（断点），重跑从 current_step+1 继续；
 * 5. 全部步骤：SUCCESS 100%。终态起算保留期（默认 14 天）。
 */
@Component
public class TaskExecutor {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);
    private static final String NODE_ID = ManagementFactory.getRuntimeMXBean().getName();

    private final AsyncTaskMapper taskMapper;
    private final TaskStepStore store;
    private final TaskProperties properties;
    private final Map<String, TaskStepHandler> handlers;

    public TaskExecutor(AsyncTaskMapper taskMapper,
                        TaskStepStore store,
                        TaskProperties properties,
                        List<TaskStepHandler> handlerList) {
        this.taskMapper = taskMapper;
        this.store = store;
        this.properties = properties;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(TaskStepHandler::type, Function.identity()));
    }

    /** 认领并执行一个任务；由调度线程池调用。任何异常都不得逃出工作线程。 */
    public void claimAndRun(Long taskId) {
        // 工作线程池化复用：认领前无条件清空 MDC，绝不带着上一任务（或调度线程经装饰器
        // 传入）的 traceId/合同号/账号执行本任务——这是后台日志串到别人合同号的第一道闸。
        TraceContext.clear();
        // 续传发起请求的 TraceId 到工作线程：后台全部步骤日志与提交请求同号。
        // 记录查不到（罕见：与清理/取消竞态）时保持空上下文执行：doRun 会重读并退出，
        // 此时日志无合同号也比挂在上一单合同号下正确。
        AsyncTask traceSource = taskMapper.selectById(taskId);
        if (traceSource != null) {
            bindTraceContext(traceSource);
        }
        try {
            doRun(taskId);
        } catch (Throwable t) {
            // 兜底：未预期异常也要把任务从 RUNNING 解套，避免悬挂
            log.error("任务 {} 执行循环异常，尝试置 FAILED", taskId, t);
            safeFail(taskId, -1, "执行器异常: " + rootMessage(t));
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 把任务的日志上下文<b>权威</b>绑定到当前工作线程（空值即摘除，不残留旧值）：
     * traceId（apt-6 起的任务必有；旧数据缺失时用任务号兜底，后台日志仍可整体聚合）、
     * 合同号（bizRef）、发起人（operator）。
     */
    private void bindTraceContext(AsyncTask task) {
        String traceId = task.getTraceId();
        if (traceId == null || traceId.isEmpty()) {
            traceId = task.getTaskNo();
        }
        TraceContext.setTraceId(traceId);
        TraceContext.bindContract(task.getBizRef());
        TraceContext.setUsername(task.getOperator());
    }

    private void doRun(Long taskId) {
        AsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !AsyncTask.STATUS_PENDING.equals(task.getStatus())) {
            return;
        }
        String lockedBy = NODE_ID + "#" + Thread.currentThread().getName();
        if (!store.claim(task, lockedBy)) {
            return;
        }
        task.setLockedBy(lockedBy);

        TaskStepHandler handler = handlers.get(task.getTaskType());
        if (handler == null) {
            store.markFailed(task, task.getCurrentStep() + 1, "无该任务类型的处理器: " + task.getTaskType(),
                    properties.getRetainDays());
            return;
        }

        while (true) {
            // 每个一致步骤执行前重新读取取消标记：停在下个一致步骤边界
            Boolean cancelRequested = taskMapper.selectById(taskId).getCancelRequested();
            int nextStep = task.getCurrentStep() + 1;
            if (Boolean.TRUE.equals(cancelRequested)) {
                store.markCancelled(task, task.getCurrentStep(), properties.getRetainDays());
                log.info("任务 {} 已取消，停在第 {} 步（{}%）",
                        task.getTaskNo(), task.getCurrentStep(), task.getProgress());
                return;
            }
            if (nextStep > task.getTotalSteps()) {
                store.markSuccess(task, task.getResultJson(), properties.getRetainDays());
                return;
            }

            StepResult result;
            try {
                result = handler.executeStep(task, nextStep);
            } catch (Exception e) {
                String reason = rootMessage(e);
                store.markFailed(task, nextStep, truncate(reason), properties.getRetainDays());
                log.warn("任务 {} 第 {} 步[{}]失败，已记断点可重跑: {}",
                        task.getTaskNo(), nextStep, stepName(handler, nextStep), reason);
                return;
            }

            if (result.getOutcome() == StepOutcome.WAIT) {
                LocalDateTime nextRun = handler instanceof SignArchiveStepHandler
                        ? ((SignArchiveStepHandler) handler).nextRunAfterWait()
                        : LocalDateTime.now().plusSeconds(5);
                // CAS 退回：等待期间若已申请取消则退回失败，就地停在当前一致边界，
                // 绝不降回可被重新捞取/被签署回调唤醒的 PENDING（否则取消被吞、后台续跑）
                if (!store.backToPending(task, nextRun)) {
                    store.markCancelled(task, task.getCurrentStep(), properties.getRetainDays());
                    log.info("任务 {} 等待外部条件期间已申请取消，停在第 {} 步（{}%），不退回 PENDING",
                            task.getTaskNo(), task.getCurrentStep(), task.getProgress());
                    return;
                }
                log.info("任务 {} 第 {} 步等待外部条件，{} 后重领", task.getTaskNo(), nextStep, nextRun);
                return;
            }
            if (result.getOutcome() == StepOutcome.CANCELLED) {
                store.markCancelled(task, task.getCurrentStep(), properties.getRetainDays());
                return;
            }
            // ADVANCE：进度随独立事务提交
            if (!store.advance(task)) {
                // 任务已不在 RUNNING（极少数并发回收），停止本次执行
                return;
            }
            if (nextStep >= task.getTotalSteps()) {
                store.markSuccess(task, result.getResultJson(), properties.getRetainDays());
                log.info("任务 {} 执行成功 100%", task.getTaskNo());
                return;
            }
        }
    }

    /** 启动恢复：上一进程 RUNNING 的任务退回 PENDING（步骤幂等，续跑安全）。 */
    public int recoverOrphaned() {
        int n = taskMapper.recoverRunning(LocalDateTime.now());
        if (n > 0) {
            log.info("启动回收 {} 个 RUNNING 任务为 PENDING，等待断点续跑", n);
        }
        return n;
    }

    /** 取一批到期可认领的 PENDING 任务 id。 */
    public List<Long> findDue(int limit) {
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.selectList(new QueryWrapper<AsyncTask>()
                        .select("id")
                        .eq("status", AsyncTask.STATUS_PENDING)
                        .and(w -> w.isNull("next_run_at").or().le("next_run_at", now))
                        .orderByAsc("id")
                        .last("LIMIT " + limit))
                .stream().map(AsyncTask::getId).collect(Collectors.toList());
    }

    /** 清理超过保留期的终态任务记录（保留 14 天）。 */
    public int deleteExpired() {
        return taskMapper.deleteExpired(LocalDateTime.now());
    }

    private void safeFail(Long taskId, int step, String reason) {
        try {
            AsyncTask task = taskMapper.selectById(taskId);
            if (task != null && AsyncTask.STATUS_RUNNING.equals(task.getStatus())) {
                store.markFailed(task, step, truncate(reason), properties.getRetainDays());
            }
        } catch (Exception ignore) {
            // 兜底分支不再抛
        }
    }

    private String truncate(String reason) {
        int max = properties.getFailReasonMaxLen();
        return reason == null ? "未知错误" : (reason.length() <= max ? reason : reason.substring(0, max));
    }

    private String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + (c.getMessage() == null ? "无错误信息" : c.getMessage());
    }

    private String stepName(TaskStepHandler handler, int stepNo) {
        return handler instanceof SignArchiveStepHandler
                ? SignArchiveStepHandler.STEP_NAMES.get(stepNo - 1)
                : ("step-" + stepNo);
    }
}
