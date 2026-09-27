package com.evops.apartment.mask;

/**
 * 单个脱敏类型的规则：保留头部 keepPrefix 位、尾部 keepSuffix 位，中间用 maskChar 重复填充。
 * 全部可热更新，字段基本类型 + 不可变快照由 {@link SensitiveRuleRegistry} 保证可见性。
 */
public class SensitiveRule {
    private int keepPrefix;
    private int keepSuffix;
    private String maskChar = "*";

    public int getKeepPrefix() { return keepPrefix; }
    public void setKeepPrefix(int keepPrefix) { this.keepPrefix = keepPrefix; }
    public int getKeepSuffix() { return keepSuffix; }
    public void setKeepSuffix(int keepSuffix) { this.keepSuffix = keepSuffix; }
    public String getMaskChar() { return maskChar; }
    public void setMaskChar(String maskChar) { this.maskChar = maskChar; }

    public SensitiveRule copy() {
        SensitiveRule r = new SensitiveRule();
        r.keepPrefix = keepPrefix;
        r.keepSuffix = keepSuffix;
        r.maskChar = maskChar;
        return r;
    }
}
