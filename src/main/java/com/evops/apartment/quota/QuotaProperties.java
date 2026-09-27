package com.evops.apartment.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出租率额度预占配置（evops.quota.*）。
 */
@ConfigurationProperties(prefix = "evops.quota")
public class QuotaProperties {
    /** 超时扫描总开关（应急时可停自动释放；已预占额度需人工取消释放）。 */
    private boolean enabled = true;
    /** 预占有效期分钟数，超时未确认自动释放（默认 20 分钟）。 */
    private int holdTtlMinutes = 20;
    /** 到期预占扫描间隔毫秒。 */
    private long sweepIntervalMs = 30000;
    /** 启动后首次扫描延迟毫秒。 */
    private long sweepInitialDelayMs = 10000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getHoldTtlMinutes() { return holdTtlMinutes; }
    public void setHoldTtlMinutes(int holdTtlMinutes) { this.holdTtlMinutes = holdTtlMinutes; }
    public long getSweepIntervalMs() { return sweepIntervalMs; }
    public void setSweepIntervalMs(long sweepIntervalMs) { this.sweepIntervalMs = sweepIntervalMs; }
    public long getSweepInitialDelayMs() { return sweepInitialDelayMs; }
    public void setSweepInitialDelayMs(long sweepInitialDelayMs) { this.sweepInitialDelayMs = sweepInitialDelayMs; }
}
