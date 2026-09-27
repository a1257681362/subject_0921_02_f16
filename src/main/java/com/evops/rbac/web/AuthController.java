package com.evops.rbac.web;

import com.evops.common.ApiResponse;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.EffectiveScope;
import com.evops.rbac.service.LoginService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录/登出/当前身份。登录与登出在 Shiro 链路上放行，
 * 真正的身份校验由 {@link TokenAuthFilter} 逐请求完成。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final LoginService loginService;

    public AuthController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Validated @RequestBody LoginRequest req) {
        LoginService.LoginResult result = loginService.login(req.getUsername(), req.getPassword());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", result.getToken());
        data.put("expiresAt", result.getExpiresAt());
        data.put("user", userView(result.getUser()));
        return ApiResponse.ok(data);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String token = request.getHeader(TokenAuthFilter.TOKEN_HEADER);
        if (token != null) {
            loginService.logout(token.trim());
        }
        return ApiResponse.ok(null);
    }

    /** 当前身份：菜单/按钮并集、最宽数据范围；前端据此渲染。无有效令牌回 401。 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        CurrentUser current = CurrentUserHolder.get();
        if (current == null) {
            throw new com.evops.rbac.core.RbacUnauthorizedException("未登录或登录已失效");
        }
        return ApiResponse.ok(userView(current));
    }

    static Map<String, Object> userView(CurrentUser user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("username", user.getUsername());
        view.put("displayName", user.getDisplayName());
        view.put("roles", user.roleCodes());
        view.put("menus", user.menuCodes());
        view.put("buttons", user.buttonCodes());
        EffectiveScope scope = user.getScope();
        Map<String, Object> scopeView = new LinkedHashMap<>();
        scopeView.put("level", scope.getLevel().name());
        scopeView.put("communities", scope.getCommunities());
        scopeView.put("grantedBy", scope.getGrantedBy());
        view.put("dataScope", scopeView);
        return view;
    }
}
