package com.evops.apartment.report;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * apt-8 签约结果报告装配。
 */
@Configuration
@EnableConfigurationProperties(ReportProperties.class)
public class ReportConfig {
}
