package com.evops.apartment.task.engine;

import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务进度/状态落库。每个方法都是独立短事务（REQUIRES_NEW）：
 *
 * - 步骤体（业务库写入/平台调用）与进度推进分离，步骤一提交即对外可见进度，
 *   这正是“取消停在下个一致步骤”的提交边界；
 * - 平台调用不裹在长事务里（不占数据库连接）；
 * - 业务步骤自身的事务保证库内一致性（如归档行与租约更新同生共死）。
 */
@Component
public class TaskStepStore {
    private final AsyncTaskMapper taskMapper;

    public TaskStepStore(AsyncTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /** 认领：CAS 保证多执行者只有一个领到。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(AsyncTask task, String lockedBy) {
        return taskMapper.claim(task.getId(), lockedBy, LocalDateTime.now()) == 1;
    }

    /**
     * 完成一个一致步骤：currentStep+1、progress+5，进度行随本独立事务立即提交。
     * 仅当任务仍归本执行者且为 RUNNING 时生效（被取消/回收的任务不再推进）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean advance(AsyncTask task) {
        int next = task.getCurrentStep() + 1;
        if (next > task.getTotalSteps()) {
            next = task.getTotalSteps();
        }
        LocalDateTime now = LocalDateTime.now();
        AsyncTask update = new AsyncTask();
        update.setId(task.getId());
        update.setCurrentStep(next);
        update.setProgress(next * 100 / task.getTotalSteps());
        update.setUpdatedAt(now);
        int rows = taskMapper.update(update,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AsyncTask>lambdaUpdate()
                        .eq(AsyncTask::getId, task.getId())
                        .eq(AsyncTask::getStatus, AsyncTask.STATUS_RUNNING)
                        .eq(AsyncTask::getLockedBy, task.getLockedBy()));
        if (rows == 1) {
            task.setCurrentStep(next);
            task.setProgress(update.getProgress());
        }
        return rows == 1;
    }

    /**
     * 保存断点上下文（步骤产物）。与进度推进同一提交边界，调用方在步骤事务内使用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveCheckpoint(AsyncTask task, String checkpointJson) {
        AsyncTask update = new AsyncTask();
        update.setId(task.getId());
        update.setCheckpoint(checkpointJson);
        update.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(update);
        task.setCheckpoint(checkpointJson);
    }

    /**
     * 等待外部条件：退回 PENDING 并设定下次认领时间，释放执行槽。
     *
     * 条件更新（CAS）：仅当任务仍归本执行者、为 RUNNING 且此间无人申请取消时才退回 PENDING。
     * 若等待期间取消标记已置位（取消请求与本次退回并发/略早），返回 {@code false}，
     * 由调用方就地落 CANCELLED——绝不把一个“已申请取消”的任务降回可被重新捞取的 PENDING，
     * 否则会出现“进度条停了、后台却被重新认领续跑”的取消被吞掉问题。
     *
     * @return true=已退回 PENDING；false=任务不再满足退回前提（已申请取消，或已被回收/改态），
     *         调用方不得再按 PENDING 处理
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean backToPending(AsyncTask task, LocalDateTime nextRunAt) {
        AsyncTask update = new AsyncTask();
        update.setId(task.getId());
        update.setStatus(AsyncTask.STATUS_PENDING);
        update.setLockedBy(null);
        update.setLockAt(null);
        update.setNextRunAt(nextRunAt);
        update.setUpdatedAt(LocalDateTime.now());
        int rows = taskMapper.update(update,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AsyncTask>lambdaUpdate()
                        .eq(AsyncTask::getId, task.getId())
                        .eq(AsyncTask::getStatus, AsyncTask.STATUS_RUNNING)
                        .eq(AsyncTask::getLockedBy, task.getLockedBy())
                        .eq(AsyncTask::getCancelRequested, false));
        if (rows != 1) {
            return false;
        }
        task.setStatus(AsyncTask.STATUS_PENDING);
        task.setLockedBy(null);
        task.setLockAt(null);
        task.setNextRunAt(nextRunAt);
        return true;
    }

    /** 失败：记断点步骤与原因，进入 FAILED，起算保留期。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(AsyncTask task, int failStep, String reason, int retainDays) {
        LocalDateTime now = LocalDateTime.now();
        AsyncTask update = new AsyncTask();
        update.setId(task.getId());
        update.setStatus(AsyncTask.STATUS_FAILED);
        update.setFailStep(failStep);
        update.setFailReason(reason);
        update.setLockedBy(null);
        update.setLockAt(null);
        update.setFinishedAt(now);
        update.setRetainUntil(now.plusDays(retainDays));
        update.setUpdatedAt(now);
        taskMapper.updateById(update);
        task.setStatus(AsyncTask.STATUS_FAILED);
        task.setFailStep(failStep);
        task.setFailReason(reason);
        task.setLockedBy(null);
        task.setFinishedAt(now);
        task.setRetainUntil(update.getRetainUntil());
    }

    /** 取消：在一致步骤边界落 CANCELLED，进度停在当前 5 的整数倍。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelled(AsyncTask task, int atStep, int retainDays) {
        LocalDateTime now = LocalDateTime.now();
        AsyncTask update = new AsyncTask();
        update.setId(task.getId());
        update.setStatus(AsyncTask.STATUS_CANCELLED);
        update.setCurrentStep(atStep);
        update.setProgress(atStep * 100 / task.getTotalSteps());
        update.setLockedBy(null);
        update.setLockAt(null);
        update.setFinishedAt(now);
        update.setRetainUntil(now.plusDays(retainDays));
        update.setUpdatedAt(now);
        taskMapper.updateById(update);
        task.setStatus(AsyncTask.STATUS_CANCELLED);
        task.setCurrentStep(atStep);
        task.setProgress(update.getProgress());
        task.setLockedBy(null);
        task.setFinishedAt(now);
        task.setRetainUntil(update.getRetainUntil());
    }

    /** 成功：100% + 结果摘要，起算保留期。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(AsyncTask task, String resultJson, int retainDays) {
        LocalDateTime now = LocalDateTime.now();
        AsyncTask update = new AsyncTask();
        update.setId(task.getId());
        update.setStatus(AsyncTask.STATUS_SUCCESS);
        update.setProgress(100);
        update.setCurrentStep(task.getTotalSteps());
        update.setResultJson(resultJson);
        update.setLockedBy(null);
        update.setLockAt(null);
        update.setFinishedAt(now);
        update.setRetainUntil(now.plusDays(retainDays));
        update.setUpdatedAt(now);
        taskMapper.updateById(update);
        task.setStatus(AsyncTask.STATUS_SUCCESS);
        task.setProgress(100);
        task.setCurrentStep(task.getTotalSteps());
        task.setResultJson(resultJson);
        task.setLockedBy(null);
        task.setFinishedAt(now);
        task.setRetainUntil(update.getRetainUntil());
    }
}
