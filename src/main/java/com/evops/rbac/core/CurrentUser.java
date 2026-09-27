package com.evops.rbac.core;

import com.evops.rbac.entity.RbacPermission;
import com.evops.rbac.entity.RbacRole;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 每请求实时组装的当前身份：多角色功能权限取并集，数据范围取最宽。
 * 对象不缓存——角色停用、授权调整在下一次请求组装时即时生效。
 */
public class CurrentUser {
    private final Long userId;
    private final String username;
    private final String displayName;
    private final List<RbacRole> roles;
    private final List<RbacPermission> permissions;
    private final Set<String> permissionCodes;
    private final EffectiveScope scope;

    public CurrentUser(Long userId, String username, String displayName,
                       List<RbacRole> roles, List<RbacPermission> permissions, EffectiveScope scope) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roles = roles == null ? new ArrayList<>() : roles;
        this.permissions = permissions == null ? new ArrayList<>() : permissions;
        this.permissionCodes = new LinkedHashSet<>();
        for (RbacPermission p : this.permissions) {
            permissionCodes.add(p.getPermCode());
        }
        this.scope = scope == null ? EffectiveScope.self() : scope;
    }

    public boolean hasPermission(String code) {
        return permissionCodes.contains(code);
    }

    /** 菜单并集：前端按此渲染菜单。 */
    public List<String> menuCodes() {
        return codesOfType(RbacPermission.TYPE_MENU);
    }

    /** 按钮并集：前端按此渲染/置灰按钮。 */
    public List<String> buttonCodes() {
        return codesOfType(RbacPermission.TYPE_BUTTON);
    }

    private List<String> codesOfType(String type) {
        Set<String> codes = new LinkedHashSet<>();
        for (RbacPermission p : permissions) {
            if (type.equals(p.getPermType())) {
                codes.add(p.getPermCode());
            }
        }
        return new ArrayList<>(codes);
    }

    public List<String> roleCodes() {
        List<String> codes = new ArrayList<>();
        for (RbacRole role : roles) {
            codes.add(role.getRoleCode());
        }
        return codes;
    }

    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public List<RbacRole> getRoles() { return roles; }
    public List<RbacPermission> getPermissions() { return permissions; }
    public Set<String> getPermissionCodes() { return permissionCodes; }
    public EffectiveScope getScope() { return scope; }
}
