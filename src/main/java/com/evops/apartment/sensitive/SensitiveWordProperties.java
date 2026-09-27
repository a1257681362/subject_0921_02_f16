package com.evops.apartment.sensitive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DFA 敏感词过滤配置，前缀 evops.sensitive-word。
 * enabled=false 为应急总开关（排障窗口临时关闭，平时保持 true）。
 */
@ConfigurationProperties(prefix = "evops.sensitive-word")
public class SensitiveWordProperties {
    private boolean enabled = true;
    /** 命中打码时使用的字符（取首字符）。 */
    private String maskChar = "*";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMaskChar() { return maskChar; }
    public void setMaskChar(String maskChar) { this.maskChar = maskChar; }
}
