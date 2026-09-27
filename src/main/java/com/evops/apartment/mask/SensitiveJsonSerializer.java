package com.evops.apartment.mask;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

/**
 * 接口出口脱敏序列化器：从字段上的 {@link Sensitive} 注解读取类型，
 * 运行时向 {@link SensitiveRuleRegistry} 取最新规则，因此热加载对接口立即生效。
 */
public class SensitiveJsonSerializer extends JsonSerializer<String> implements ContextualSerializer {
    private SensitiveType type;

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        SensitiveRuleRegistry registry = SensitiveRuleRegistry.getInstance();
        gen.writeString(registry == null ? value : registry.mask(type, value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) {
        Sensitive sensitive = property == null ? null : property.getAnnotation(Sensitive.class);
        if (sensitive == null && property != null) {
            sensitive = property.getContextAnnotation(Sensitive.class);
        }
        if (sensitive == null) {
            return this;
        }
        SensitiveJsonSerializer serializer = new SensitiveJsonSerializer();
        serializer.type = sensitive.value();
        return serializer;
    }
}
