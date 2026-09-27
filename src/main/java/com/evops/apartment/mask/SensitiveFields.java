package com.evops.apartment.mask;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 非 JSON 出口（租约导出、日志）读取脱敏值的统一入口：与接口一样只认字段上的
 * {@link Sensitive} 注解，规则仍取自 {@link SensitiveRuleRegistry}，保证各出口口径一致。
 *
 * <p>apt-12 起支持嵌套对象递归脱敏：字段值是嵌套 bean / Map / 集合 / 数组时，
 * 深度遍历整个对象图，凡带 {@link Sensitive} 的字段一律按类型规则脱敏，
 * 不再只处理实体上的扁平 String 字段（安全抽查发现的紧急联系人电话明文即此缺陷）。
 * 日志出口经 {@link #maskValue} / {@link #toMaskedJson} 走同一套规则。
 */
public final class SensitiveFields {
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Field>> FIELD_LIST_CACHE = new ConcurrentHashMap<>();
    /** 脱敏后的对象图只含 Map/List/标量，用普通 ObjectMapper 渲染即可（不触发任何注解序列化器）。 */
    private static final ObjectMapper PLAIN_WRITER = new ObjectMapper();

    private SensitiveFields() {
    }

    /**
     * 读取字段并按注解脱敏。带 @Sensitive 的字段经注册表处理；标量字段原样转字符串，
     * null 返回空串；嵌套对象/集合/Map 递归脱敏后渲染为 JSON（字段声明序）。
     */
    public static String readAsString(Object bean, String fieldName) {
        if (bean == null) {
            return "";
        }
        Field field = findField(bean.getClass(), fieldName);
        if (field == null) {
            return "";
        }
        Object value;
        try {
            field.setAccessible(true);
            value = field.get(bean);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取字段失败: " + fieldName, e);
        }
        if (value == null) {
            return "";
        }
        Sensitive sensitive = field.getAnnotation(Sensitive.class);
        if (sensitive != null) {
            return maskValue(sensitive.value(), value.toString());
        }
        if (isScalar(value)) {
            return value.toString();
        }
        // 嵌套对象/集合/Map：递归脱敏（对象图内部带 @Sensitive 的成员一律按规则处理）
        return toMaskedJson(value);
    }

    /**
     * 日志出口（单个敏感值）：按类型走注册表同一套规则，null 安全。
     * 日志语句打印姓名/手机号/证件号等敏感值时一律经本方法，不直接拼明文。
     */
    public static String maskValue(SensitiveType type, String value) {
        if (value == null) {
            return null;
        }
        SensitiveRuleRegistry registry = SensitiveRuleRegistry.getInstance();
        return registry == null ? value : registry.mask(type, value);
    }

    /**
     * 日志出口（整个对象）：对象图递归脱敏后渲染 JSON。
     * 渲染口径与接口对齐：{@link Sensitive} 字段脱敏、{@link JsonIgnore} 字段不输出；
     * 嵌套 bean / Map / 集合 / 数组逐级深入，循环引用截断为 null 防死循环。
     */
    public static String toMaskedJson(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence) {
            return value.toString();
        }
        if (isScalar(value)) {
            return value.toString();
        }
        Object masked = maskGraph(value, Collections.newSetFromMap(new IdentityHashMap<>()));
        try {
            return PLAIN_WRITER.writeValueAsString(masked);
        } catch (Exception e) {
            throw new IllegalStateException("脱敏渲染失败: " + value.getClass().getName(), e);
        }
    }

    /** 递归脱敏核心：返回只含 Map/List/标量的脱敏对象图。 */
    private static Object maskGraph(Object value, Set<Object> visiting) {
        if (value == null) {
            return null;
        }
        if (value instanceof TemporalAccessor || value instanceof java.util.Date) {
            // 日期时间渲染为字符串：PLAIN_WRITER 未注册 JavaTimeModule，直接序列化会抛异常
            return value.toString();
        }
        if (isScalar(value)) {
            return value;
        }
        if (visiting.contains(value)) {
            // 循环引用：截断为 null，避免无限递归（正常业务对象图不会触发）
            return null;
        }
        visiting.add(value);
        try {
            if (value instanceof Map<?, ?>) {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    out.put(e.getKey() == null ? null : e.getKey().toString(), maskGraph(e.getValue(), visiting));
                }
                return out;
            }
            if (value instanceof Collection<?>) {
                List<Object> out = new ArrayList<>();
                for (Object element : (Collection<?>) value) {
                    out.add(maskGraph(element, visiting));
                }
                return out;
            }
            if (value.getClass().isArray()) {
                List<Object> out = new ArrayList<>();
                int len = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < len; i++) {
                    out.add(maskGraph(java.lang.reflect.Array.get(value, i), visiting));
                }
                return out;
            }
            // 普通 bean：逐字段遍历（含父类），@Sensitive 脱敏、@JsonIgnore 跳过、其余递归
            Map<String, Object> out = new LinkedHashMap<>();
            for (Field field : allFields(value.getClass())) {
                if (field.getAnnotation(JsonIgnore.class) != null) {
                    continue;
                }
                Object fieldValue;
                try {
                    field.setAccessible(true);
                    fieldValue = field.get(value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("读取字段失败: " + field.getName(), e);
                }
                Sensitive sensitive = field.getAnnotation(Sensitive.class);
                if (sensitive != null) {
                    out.put(field.getName(),
                            fieldValue == null ? null : maskValue(sensitive.value(), fieldValue.toString()));
                } else {
                    out.put(field.getName(), maskGraph(fieldValue, visiting));
                }
            }
            return out;
        } finally {
            visiting.remove(value);
        }
    }

    /** 标量（字符串/数字/布尔/枚举/日期时间等）：原样输出，无需递归。 */
    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof TemporalAccessor
                || value instanceof java.util.Date;
    }

    private static Field findField(Class<?> clazz, String name) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> fields = new ConcurrentHashMap<>();
            Class<?> current = c;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    fields.putIfAbsent(f.getName(), f);
                }
                current = current.getSuperclass();
            }
            return fields;
        }).get(name);
    }

    /** 类层级全部实例字段（声明序，子类在前父类在后），跳过 static/synthetic。 */
    private static List<Field> allFields(Class<?> clazz) {
        return FIELD_LIST_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> fields = new ArrayList<>();
            Class<?> current = c;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers())) {
                        fields.add(f);
                    }
                }
                current = current.getSuperclass();
            }
            return Collections.unmodifiableList(fields);
        });
    }
}
