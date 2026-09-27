package com.evops.rbac.web;

import com.evops.common.trace.TraceContext;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.RbacProperties;
import com.evops.rbac.core.RbacUnauthorizedException;
import com.evops.rbac.service.LoginService;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 令牌鉴权过滤器：从 X-Auth-Token 解析身份并写入请求级上下文。
 * 令牌不承载授权——身份对象每请求实时查库组装，角色/账号停用在本请求即收权。
 * 令牌缺失或无效时不拦截请求本身，由 {@link PermissionInterceptor} 按接口要求决定 401，
 * 因此登录接口等匿名接口即便带了错令牌也不受影响。
 */
public class TokenAuthFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-Auth-Token";

    private final LoginService loginService;
    private final RbacProperties properties;

    public TokenAuthFilter(LoginService loginService, RbacProperties properties) {
        this.loginService = loginService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (properties.isEnabled()) {
                String token = request.getHeader(TOKEN_HEADER);
                if (token != null && !token.trim().isEmpty()) {
                    try {
                        CurrentUserHolder.set(loginService.resolve(token));
                        // 账号入日志上下文：同一 TraceId 的日志可定位到操作人
                        TraceContext.setUsername(CurrentUserHolder.get().getUsername());
                    } catch (RbacUnauthorizedException ex) {
                        // 无效/过期/停用：按匿名继续，受保护接口由拦截器统一回 401
                        CurrentUserHolder.clear();
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // 只清身份键，TraceId 由外层 TraceFilter 统一清理
            MDC.remove(TraceContext.USERNAME);
            CurrentUserHolder.clear();
        }
    }
}
