package com.evops.apartment.mask;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 打在需要脱敏的字符串字段上（实体/DTO 均可）。
 * 接口返回由 Jackson 序列化器自动脱敏；租约导出通过 {@link SensitiveFields} 读同一注解，
 * 两条出口共用 {@link SensitiveType} 对应的集中规则，口径一致。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@JacksonAnnotationsInside
@JsonSerialize(using = SensitiveJsonSerializer.class)
public @interface Sensitive {
    SensitiveType value();
}
