package com.evops.apartment.quota;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 额度预占模型装配：开启 evops.quota.* 配置绑定。
 * 定时扫描复用 apt-5 TaskConfig 上的 @EnableScheduling，不重复开启。
 */
@Configuration
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaConfig {
}
