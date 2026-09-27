package com.evops.common.opslog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * apt-6 运维日志采集与保留配置：
 *
 * - enabled=false 应急关闭 DB 采集（文件日志照常，接口查不到新日志）；
 * - 库内保留固定 14 天（与文件滚动口径一致），每日清理；
 * - 查询窗口强制 14 天，单次最多返回 limit 条，防止管家一次拖全表；
 * - 异步入库有界队列：满则丢新并计数，绝不阻塞业务线程。
 */
@ConfigurationProperties(prefix = "evops.ops-log")
public class OpsLogProperties {
    private boolean enabled = true;
    private int retainDays = 14;
    private int queueCapacity = 2000;
    /** 一次批量落库的最大条数。 */
    private int flushBatchSize = 200;
    /** 入库空转时的最长落库延迟（毫秒）。 */
    private long flushIntervalMs = 1000;
    /** 查询接口单次返回上限与时间窗（天）。 */
    private int queryLimit = 200;
    private int queryWindowDays = 14;
    private String cleanupCron = "0 23 1 * * ?";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getRetainDays() { return retainDays; }
    public void setRetainDays(int retainDays) { this.retainDays = retainDays; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getFlushBatchSize() { return flushBatchSize; }
    public void setFlushBatchSize(int flushBatchSize) { this.flushBatchSize = flushBatchSize; }
    public long getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
    public int getQueryLimit() { return queryLimit; }
    public void setQueryLimit(int queryLimit) { this.queryLimit = queryLimit; }
    public int getQueryWindowDays() { return queryWindowDays; }
    public void setQueryWindowDays(int queryWindowDays) { this.queryWindowDays = queryWindowDays; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
