package com.evops.apartment.sensitive;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DFA 敏感词组件装配。
 */
@Configuration
@EnableConfigurationProperties(SensitiveWordProperties.class)
public class SensitiveWordConfig {
}
