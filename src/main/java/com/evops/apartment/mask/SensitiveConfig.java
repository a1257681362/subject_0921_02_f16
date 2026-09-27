package com.evops.apartment.mask;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 脱敏组件装配：规则注册表是接口序列化与导出的共用出口。
 */
@Configuration
@EnableConfigurationProperties(SensitiveRuleProperties.class)
public class SensitiveConfig {

    @Bean
    public SensitiveRuleRegistry sensitiveRuleRegistry(SensitiveRuleProperties props) {
        return new SensitiveRuleRegistry(props);
    }
}
