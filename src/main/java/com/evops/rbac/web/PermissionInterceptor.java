package com.evops.rbac.web;

import com.evops.common.ApiResponse;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.RbacForbiddenException;
import com.evops.rbac.core.RbacProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 功能权限拦截：按 {@link RequirePermission} 校验多角色并集后的权限点。
 * 未登录（无身份）401；已登录但权限不足 403。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    private final RbacProperties properties;
    private final ObjectMapper objectMapper;

    public PermissionInterceptor(RbacProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!properties.isEnabled() || !(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod method = (HandlerMethod) handler;
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (required == null || required.value().length == 0) {
            return true;
        }
        CurrentUser user = CurrentUserHolder.get();
        if (user == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, "未登录或登录已失效");
            return false;
        }
        for (String code : required.value()) {
            if (!user.hasPermission(code)) {
                throw new RbacForbiddenException("无权访问: 缺少权限 " + code);
            }
        }
        return true;
    }

    /** 过滤器阶段已结束、异常处理器不覆盖拦截器响应，401 需在此直接落 JSON。 */
    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
