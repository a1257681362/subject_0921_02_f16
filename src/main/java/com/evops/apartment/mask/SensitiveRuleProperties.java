package com.evops.apartment.mask;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * 脱敏规则配置，前缀 evops.sensitive。
 * 既承接 application.yml 默认值，也作为外部规则文件热加载时的绑定目标。
 *
 * 示例（application.yml 或 config/sensitive-rules.yml）：
 * evops:
 *   sensitive:
 *     enabled: true
 *     rules:
 *       name:    { keep-prefix: 1, keep-suffix: 0, mask-char: "*" }
 *       phone:   { keep-prefix: 3, keep-suffix: 4, mask-char: "*" }
 *       id-card: { keep-prefix: 6, keep-suffix: 4, mask-char: "*" }
 */
@ConfigurationProperties(prefix = "evops.sensitive")
public class SensitiveRuleProperties {
    private boolean enabled = true;
    /** 外部规则文件路径，变更后自动热加载；文件不存在时使用本配置/内置默认。 */
    private String ruleFile = "config/sensitive-rules.yml";
    private Map<SensitiveType, SensitiveRule> rules = defaultRules();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRuleFile() { return ruleFile; }
    public void setRuleFile(String ruleFile) { this.ruleFile = ruleFile; }
    public Map<SensitiveType, SensitiveRule> getRules() { return rules; }
    public void setRules(Map<SensitiveType, SensitiveRule> rules) { this.rules = rules; }

    /** 内置兜底规则：姓名留姓、手机留前 3 后 4、证件号留前 6 后 4。 */
    public static Map<SensitiveType, SensitiveRule> defaultRules() {
        Map<SensitiveType, SensitiveRule> rules = new EnumMap<>(SensitiveType.class);
        rules.put(SensitiveType.NAME, rule(1, 0, "*"));
        rules.put(SensitiveType.PHONE, rule(3, 4, "*"));
        rules.put(SensitiveType.ID_CARD, rule(6, 4, "*"));
        return rules;
    }

    static SensitiveRule rule(int prefix, int suffix, String maskChar) {
        SensitiveRule r = new SensitiveRule();
        r.setKeepPrefix(prefix);
        r.setKeepSuffix(suffix);
        r.setMaskChar(maskChar);
        return r;
    }

    /** 深拷贝，作为外部文件覆盖前的底（未在文件中出现的类型沿用内置/启动默认）。 */
    public SensitiveRuleProperties copy() {
        SensitiveRuleProperties c = new SensitiveRuleProperties();
        c.enabled = enabled;
        Map<SensitiveType, SensitiveRule> copied = new EnumMap<>(SensitiveType.class);
        for (Map.Entry<SensitiveType, SensitiveRule> e : rules.entrySet()) {
            copied.put(e.getKey(), e.getValue().copy());
        }
        c.rules = copied;
        return c;
    }
}
