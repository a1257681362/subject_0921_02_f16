package com.evops.common.opslog;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * apt-6 运维日志采集装配：注册 {@link OpsLogProperties}；
 * 异步入库 Sink 由 {@link OpsLogSinkService} 的 @Service 生命周期自行注册/注销到
 * {@link OpsLogSinkRegistry}（logback appender 与 Spring 之间的静态桥）。
 */
@Configuration
@EnableConfigurationProperties(OpsLogProperties.class)
public class OpsLogConfig {
}
