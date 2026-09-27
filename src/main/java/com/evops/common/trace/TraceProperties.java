package com.evops.common.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * apt-6 全链路追踪配置。
 *
 * - enabled=false 为应急回退：入口不再承接/生成 TraceId（响应头仍原样回带在网关注入的值），
 *   日志格式与保留策略不变，排障窗口一般保持 true；
 * - 响应头名固定 X-Trace-Id，前端/网关报错时凭该头即可定位整次请求日志。
 */
@ConfigurationProperties(prefix = "evops.trace")
public class TraceProperties {
    private boolean enabled = true;
    /** 入口/响应头名；入站带同名头时沿用其值（限字母数字与中划线，防日志注入）。 */
    private String headerName = "X-Trace-Id";
    /** 入站 TraceId 合法长度区间。 */
    private int inboundMinLength = 8;
    private int inboundMaxLength = 64;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public int getInboundMinLength() { return inboundMinLength; }
    public void setInboundMinLength(int inboundMinLength) { this.inboundMinLength = inboundMinLength; }
    public int getInboundMaxLength() { return inboundMaxLength; }
    public void setInboundMaxLength(int inboundMaxLength) { this.inboundMaxLength = inboundMaxLength; }
}
