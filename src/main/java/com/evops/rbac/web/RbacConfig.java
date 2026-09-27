package com.evops.rbac.web;

import com.evops.rbac.core.RbacProperties;
import com.evops.rbac.service.LoginService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * RBAC 装配：令牌过滤器在最前置入；权限拦截器见 {@link RbacWebMvcConfig}。
 */
@Configuration
@EnableConfigurationProperties(RbacProperties.class)
public class RbacConfig {

    @Bean
    public TokenAuthFilter tokenAuthFilter(LoginService loginService, RbacProperties properties) {
        return new TokenAuthFilter(loginService, properties);
    }

    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilterRegistration(TokenAuthFilter filter) {
        FilterRegistrationBean<TokenAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("tokenAuthFilter");
        return registration;
    }
}
