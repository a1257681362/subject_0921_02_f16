package com.evops.apartment.esign;

import com.evops.apartment.esign.mapper.ESignNonceMapper;
import com.evops.apartment.esign.web.ESignCallbackFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 电子签章对接装配：mock/http 两个客户端各自按 evops.esign.provider 条件注册。
 *
 * apt-9：注册平台回调验签过滤器，仅匹配回调精确路径，顺序在 TraceFilter（+10）之后、
 * 令牌鉴权过滤器（+20）之前——回调是机器对机器、无登录令牌，验签在鉴权过滤器处不会被拦
 * （令牌过滤器对无令牌请求放行，由权限拦截器决定），但安全校验必须最前置完成。
 */
@Configuration
@EnableConfigurationProperties(ESignProperties.class)
public class ESignConfig {

    @Bean
    public ESignCallbackFilter eSignCallbackFilter(ESignProperties properties,
                                                    ESignNonceMapper nonceMapper,
                                                    ObjectMapper objectMapper) {
        return new ESignCallbackFilter(properties, nonceMapper, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<ESignCallbackFilter> eSignCallbackFilterRegistration(
            ESignCallbackFilter filter, ESignProperties properties) {
        FilterRegistrationBean<ESignCallbackFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(properties.getCallback().getPath());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        registration.setName("eSignCallbackFilter");
        return registration;
    }
}
