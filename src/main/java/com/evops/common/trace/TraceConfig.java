package com.evops.common.trace;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * apt-6 全链路追踪装配：
 *
 * - {@link TraceFilter} 以最高优先级挂在 /api/*，先于令牌鉴权过滤器，
 *   保证 401/403/参数校验失败等任何分支的日志与响应头都带同一 TraceId；
 * - 暴露 {@link MdcTaskDecorator}，线程池（如 apt-5 任务工作池）装配后请求线程异步日志自动续传。
 */
@Configuration
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfig {

    @Bean
    public TraceFilter traceFilter(TraceProperties properties) {
        return new TraceFilter(properties);
    }

    @Bean
    public FilterRegistrationBean<TraceFilter> traceFilterRegistration(TraceFilter filter) {
        FilterRegistrationBean<TraceFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        // 先于 tokenAuthFilter（HIGHEST_PRECEDENCE + 20），鉴权/异常日志都在同一 TraceId 内
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("traceFilter");
        return registration;
    }

    @Bean
    public MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }
}
