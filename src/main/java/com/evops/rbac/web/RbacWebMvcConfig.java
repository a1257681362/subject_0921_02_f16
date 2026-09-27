package com.evops.rbac.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 功能权限拦截器挂载于全部 /api/** 接口；是否拦截由接口上的 @RequirePermission 决定。
 */
@Configuration
public class RbacWebMvcConfig implements WebMvcConfigurer {
    private final PermissionInterceptor permissionInterceptor;

    public RbacWebMvcConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**");
    }
}
