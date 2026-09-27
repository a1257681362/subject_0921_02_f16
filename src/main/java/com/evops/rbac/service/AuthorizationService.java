package com.evops.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.DataScopeLevel;
import com.evops.rbac.core.EffectiveScope;
import com.evops.rbac.entity.RbacDataScope;
import com.evops.rbac.entity.RbacPermission;
import com.evops.rbac.entity.RbacRole;
import com.evops.rbac.entity.RbacUser;
import com.evops.rbac.mapper.RbacAuthorizationMapper;
import com.evops.rbac.mapper.RbacDataScopeMapper;
import com.evops.rbac.mapper.RbacUserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 授权组装：每次请求实时查库，绝不缓存。
 * 功能权限 = 全部启用角色权限点并集；数据范围 = SELF &lt; SCOPE &lt; ALL 取最宽，
 * 并记录提供最宽范围的角色，供放行审计使用。
 */
@Service
public class AuthorizationService {
    private final RbacUserMapper userMapper;
    private final RbacAuthorizationMapper authorizationMapper;
    private final RbacDataScopeMapper dataScopeMapper;

    public AuthorizationService(RbacUserMapper userMapper,
                                RbacAuthorizationMapper authorizationMapper,
                                RbacDataScopeMapper dataScopeMapper) {
        this.userMapper = userMapper;
        this.authorizationMapper = authorizationMapper;
        this.dataScopeMapper = dataScopeMapper;
    }

    /** 按账号实时组装身份；账号不存在或停用返回 null，由调用方按未认证处理。 */
    public CurrentUser assemble(Long userId) {
        RbacUser user = userMapper.selectById(userId);
        if (user == null || !RbacUser.STATUS_ACTIVE.equals(user.getStatus())) {
            return null;
        }
        List<RbacRole> roles = authorizationMapper.selectActiveRolesByUser(userId);
        List<RbacPermission> permissions = authorizationMapper.selectPermissionsByUser(userId);
        return new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
                roles, permissions, resolveScope(user, roles));
    }

    /**
     * 最宽数据范围：角色级授权与账号级直授一起参与比较。
     * 最宽为 SCOPE 时，所有处于 SCOPE 等级授权的小区取并集；
     * grantedBy 记录最宽等级的来源（角色编码 / USER 直授），逐请求写入审计。
     */
    private EffectiveScope resolveScope(RbacUser user, List<RbacRole> roles) {
        List<ScopeSource> sources = new ArrayList<>();
        for (RbacRole role : roles) {
            List<RbacDataScope> roleScopes = dataScopeMapper.selectList(new QueryWrapper<RbacDataScope>()
                    .eq("subject_type", RbacDataScope.SUBJECT_ROLE)
                    .eq("subject_id", role.getId()));
            for (RbacDataScope ds : roleScopes) {
                sources.add(new ScopeSource(parseLevel(ds.getScopeLevel()), ds.getScopeValue(),
                        role.getRoleCode()));
            }
        }
        List<RbacDataScope> userScopes = dataScopeMapper.selectList(new QueryWrapper<RbacDataScope>()
                .eq("subject_type", RbacDataScope.SUBJECT_USER)
                .eq("subject_id", user.getId()));
        for (RbacDataScope ds : userScopes) {
            sources.add(new ScopeSource(parseLevel(ds.getScopeLevel()), ds.getScopeValue(), "USER"));
        }
        if (sources.isEmpty()) {
            return EffectiveScope.self();
        }

        DataScopeLevel widest = DataScopeLevel.SELF;
        for (ScopeSource source : sources) {
            if (source.level.widerThan(widest)) {
                widest = source.level;
            }
        }
        TreeSet<String> communities = new TreeSet<>();
        TreeSet<String> grantedBy = new TreeSet<>();
        for (ScopeSource source : sources) {
            if (source.level == widest) {
                grantedBy.add(source.grantedBy);
                if (widest == DataScopeLevel.SCOPE && source.scopeValue != null) {
                    communities.add(source.scopeValue);
                }
            }
        }
        return new EffectiveScope(widest, communities, grantedBy);
    }

    private DataScopeLevel parseLevel(String raw) {
        if (raw == null) {
            return DataScopeLevel.SELF;
        }
        try {
            return DataScopeLevel.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return DataScopeLevel.SELF;
        }
    }

    private static class ScopeSource {
        final DataScopeLevel level;
        final String scopeValue;
        final String grantedBy;

        ScopeSource(DataScopeLevel level, String scopeValue, String grantedBy) {
            this.level = level;
            this.scopeValue = scopeValue;
            this.grantedBy = grantedBy;
        }
    }
}
