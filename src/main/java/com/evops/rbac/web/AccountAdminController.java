package com.evops.rbac.web;

import com.evops.common.ApiResponse;
import com.evops.rbac.service.AccountAdminService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 账号/角色启停管理：停用即时收权（角色下次请求组装即移除；账号令牌当次请求即拒并被吊销）。
 */
@RestController
@RequestMapping("/api/rbac")
@RequirePermission("rbac:admin:manage")
public class AccountAdminController {
    private final AccountAdminService accountAdminService;

    public AccountAdminController(AccountAdminService accountAdminService) {
        this.accountAdminService = accountAdminService;
    }

    @PostMapping("/users/{username}/status")
    public ApiResponse<Void> setUserStatus(@PathVariable String username,
                                           @RequestBody Map<String, Boolean> body) {
        accountAdminService.setUserStatus(username, Boolean.TRUE.equals(body.get("active")));
        return ApiResponse.ok(null);
    }

    @PostMapping("/roles/{roleCode}/status")
    public ApiResponse<Void> setRoleStatus(@PathVariable String roleCode,
                                           @RequestBody Map<String, Boolean> body) {
        accountAdminService.setRoleStatus(roleCode, Boolean.TRUE.equals(body.get("active")));
        return ApiResponse.ok(null);
    }
}
