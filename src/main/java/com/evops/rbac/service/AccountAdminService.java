package com.evops.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.rbac.entity.LoginToken;
import com.evops.rbac.entity.RbacRole;
import com.evops.rbac.entity.RbacUser;
import com.evops.rbac.mapper.LoginTokenMapper;
import com.evops.rbac.mapper.RbacRoleMapper;
import com.evops.rbac.mapper.RbacUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 账号/角色启停。授权每请求实时查库组装：
 * - 角色停用：当次请求起该角色的菜单/按钮与数据范围立即从并集中移除（即时收权）；
 * - 账号停用：当次请求起身份校验即拒（401），同时吊销其全部未过期令牌。
 */
@Service
public class AccountAdminService {
    private final RbacUserMapper userMapper;
    private final RbacRoleMapper roleMapper;
    private final LoginTokenMapper tokenMapper;

    public AccountAdminService(RbacUserMapper userMapper,
                               RbacRoleMapper roleMapper,
                               LoginTokenMapper tokenMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.tokenMapper = tokenMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setUserStatus(String username, boolean active) {
        RbacUser user = userMapper.selectOne(new QueryWrapper<RbacUser>().eq("username", username));
        if (user == null) {
            throw new IllegalArgumentException("账号不存在: " + username);
        }
        String target = active ? RbacUser.STATUS_ACTIVE : RbacUser.STATUS_DISABLED;
        if (target.equals(user.getStatus())) {
            return;
        }
        user.setStatus(target);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        if (!active) {
            // 吊销全部有效令牌：重新启用后也需重新登录，停用窗口不存在令牌复活
            for (LoginToken token : tokenMapper.selectList(new QueryWrapper<LoginToken>()
                    .eq("user_id", user.getId())
                    .eq("revoked", false))) {
                token.setRevoked(true);
                tokenMapper.updateById(token);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setRoleStatus(String roleCode, boolean active) {
        RbacRole role = roleMapper.selectOne(new QueryWrapper<RbacRole>().eq("role_code", roleCode));
        if (role == null) {
            throw new IllegalArgumentException("角色不存在: " + roleCode);
        }
        String target = active ? RbacRole.STATUS_ACTIVE : RbacRole.STATUS_DISABLED;
        if (target.equals(role.getStatus())) {
            return;
        }
        role.setStatus(target);
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);
    }
}
