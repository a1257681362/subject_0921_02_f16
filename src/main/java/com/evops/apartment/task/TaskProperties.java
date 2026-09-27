package com.evops.apartment.task;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台任务调度配置。
 */
@ConfigurationProperties(prefix = "evops.task")
public class TaskProperties {
    /** 调度总开关（应急时可停后台执行，任务登记与查询不受影响）。 */
    private boolean enabled = true;
    /** 单次派发最多认领任务数（≈工作线程数）。 */
    private int batchSize = 2;
    /** 派发扫描间隔毫秒。 */
    private long dispatchIntervalMs = 2000;
    /** 等待租客签署时，再次轮询平台的间隔毫秒。 */
    private long signPollIntervalMs = 5000;
    /** 终态任务记录保留天数。 */
    private int retainDays = 14;
    /** 失败原因落库最大长度（fail_reason 列 1024）。 */
    private int failReasonMaxLen = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public long getDispatchIntervalMs() { return dispatchIntervalMs; }
    public void setDispatchIntervalMs(long dispatchIntervalMs) { this.dispatchIntervalMs = dispatchIntervalMs; }
    public long getSignPollIntervalMs() { return signPollIntervalMs; }
    public void setSignPollIntervalMs(long signPollIntervalMs) { this.signPollIntervalMs = signPollIntervalMs; }
    public int getRetainDays() { return retainDays; }
    public void setRetainDays(int retainDays) { this.retainDays = retainDays; }
    public int getFailReasonMaxLen() { return failReasonMaxLen; }
    public void setFailReasonMaxLen(int failReasonMaxLen) { this.failReasonMaxLen = failReasonMaxLen; }
}
