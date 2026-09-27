package com.evops.apartment.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 后台任务登记：慢操作接口只登记本表立即返回，后台调度认领执行。
 *
 * 状态机：
 * PENDING ──认领──▶ RUNNING ──全部步骤提交──▶ SUCCESS
 *                     │──某步失败──▶ FAILED（fail_step/fail_reason）──retry──▶ PENDING（从断点续跑）
 *                     │──cancel_requested 在下一步边界生效──▶ CANCELLED（进度停在 5 的整数倍）
 *                     │──步骤 WAIT（等租客签署）──CAS 退回 PENDING + next_run_at（到点重领）
 *
 * 取消与「认领 PENDING→RUNNING」「等待退回 RUNNING→PENDING」可能并发翻转，靠两处 CAS 夹击闭合：
 * 等待退回仅在 cancel_requested=FALSE 时才降回 PENDING，否则就地落 CANCELLED；
 * 取消接口的条件更新落空会按下一状态有界收敛（详见 AsyncTaskService#cancel）。
 * 实例宕机：启动时把 RUNNING 回收为 PENDING（步骤幂等，续跑安全）。
 */
@TableName("t_async_task")
public class AsyncTask {
    public static final String TYPE_SIGN_ARCHIVE = "SIGN_ARCHIVE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String taskType;
    private String bizRef;
    /** 房源编号：SCOPE 角色任务列表按授权片区过滤。 */
    private String propertyCode;
    /** 发起请求的全链路 TraceId：工作线程认领后续绑，后台每步日志与发起请求同号。 */
    private String traceId;
    private String status;
    /** 0-100，每个一致步骤完成 +5。 */
    private Integer progress;
    /** 已提交完成的一致步骤序号（0..totalSteps）；断点重跑从 currentStep+1 开始。 */
    private Integer currentStep;
    private Integer totalSteps;
    private Integer failStep;
    private String failReason;
    private Boolean cancelRequested;
    private String payload;
    /** 断点上下文 JSON：跨步骤产物（平台文书标识/租约 id/签署流水号）。 */
    private String checkpoint;
    private String resultJson;
    private Integer retryCount;
    private String requestNo;
    private String operator;
    private String lockedBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lockAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime nextRunAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime retainUntil;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getBizRef() { return bizRef; }
    public void setBizRef(String bizRef) { this.bizRef = bizRef; }
    public String getPropertyCode() { return propertyCode; }
    public void setPropertyCode(String propertyCode) { this.propertyCode = propertyCode; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public Integer getCurrentStep() { return currentStep; }
    public void setCurrentStep(Integer currentStep) { this.currentStep = currentStep; }
    public Integer getTotalSteps() { return totalSteps; }
    public void setTotalSteps(Integer totalSteps) { this.totalSteps = totalSteps; }
    public Integer getFailStep() { return failStep; }
    public void setFailStep(Integer failStep) { this.failStep = failStep; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public Boolean getCancelRequested() { return cancelRequested; }
    public void setCancelRequested(Boolean cancelRequested) { this.cancelRequested = cancelRequested; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getCheckpoint() { return checkpoint; }
    public void setCheckpoint(String checkpoint) { this.checkpoint = checkpoint; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public LocalDateTime getLockAt() { return lockAt; }
    public void setLockAt(LocalDateTime lockAt) { this.lockAt = lockAt; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getRetainUntil() { return retainUntil; }
    public void setRetainUntil(LocalDateTime retainUntil) { this.retainUntil = retainUntil; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
