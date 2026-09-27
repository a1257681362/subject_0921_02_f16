package com.evops.apartment.mask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 脱敏规则注册表：接口序列化与租约导出唯一的规则来源。
 *
 * 可见性：规则快照用 volatile 引用 + 不可变 Map 发布，热加载整体替换，读侧无需加锁。
 * 静态实例：Jackson 的 {@link SensitiveJsonSerializer} 由框架 new 出、不在 Spring 容器内，
 * 故构造时把自身登记到 {@link #INSTANCE}；规则变更走同一实例，序列化器始终拿到最新规则。
 */
public class SensitiveRuleRegistry {
    private static final Logger log = LoggerFactory.getLogger(SensitiveRuleRegistry.class);
    private static volatile SensitiveRuleRegistry INSTANCE;

    private static final String DEFAULT_MASK_CHAR = "*";

    private volatile Snapshot snapshot;

    public SensitiveRuleRegistry(SensitiveRuleProperties props) {
        this.snapshot = Snapshot.from(props);
        INSTANCE = this;
    }

    public static SensitiveRuleRegistry getInstance() {
        return INSTANCE;
    }

    /** 热加载入口：用新配置整体替换快照，失败由调用方保证不传入（解析失败保留旧规则）。 */
    public void reload(SensitiveRuleProperties props) {
        Snapshot next = Snapshot.from(props);
        this.snapshot = next;
        log.info("脱敏规则已热加载：enabled={}, types={}", next.enabled, next.rules.keySet());
    }

    /**
     * 按类型脱敏。关闭脱敏、值为 null 时原样返回；
     * 未配置该类型规则时按全部遮蔽处理，避免明文漏出。
     */
    public String mask(SensitiveType type, String value) {
        if (value == null) {
            return null;
        }
        Snapshot snap = snapshot;
        if (!snap.enabled) {
            return value;
        }
        SensitiveRule rule = snap.rules.get(type);
        int prefix = rule == null ? 0 : Math.max(0, rule.getKeepPrefix());
        int suffix = rule == null ? 0 : Math.max(0, rule.getKeepSuffix());
        String maskChar = rule == null || !hasText(rule.getMaskChar())
                ? DEFAULT_MASK_CHAR : rule.getMaskChar();

        int len = value.length();
        if (len <= prefix + suffix) {
            // 保留位已覆盖全串（如单字姓名），整体遮蔽，避免原样输出
            return repeat(maskChar, len);
        }
        return value.substring(0, prefix)
                + repeat(maskChar, len - prefix - suffix)
                + value.substring(len - suffix);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    private static String repeat(String s, int times) {
        if (times <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** 不可变规则快照。 */
    private static final class Snapshot {
        private final boolean enabled;
        private final Map<SensitiveType, SensitiveRule> rules;

        private Snapshot(boolean enabled, Map<SensitiveType, SensitiveRule> rules) {
            this.enabled = enabled;
            this.rules = rules;
        }

        private static Snapshot from(SensitiveRuleProperties props) {
            Map<SensitiveType, SensitiveRule> map = new EnumMap<>(SensitiveType.class);
            if (props != null && props.getRules() != null) {
                for (Map.Entry<SensitiveType, SensitiveRule> e : props.getRules().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        map.put(e.getKey(), e.getValue().copy());
                    }
                }
            }
            return new Snapshot(props == null || props.isEnabled(),
                    Collections.unmodifiableMap(map));
        }
    }
}
