package com.evops.rbac.mapper;

import com.evops.rbac.entity.RbacPermission;
import com.evops.rbac.entity.RbacRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 授权组装查询：每次请求实时联表读取，不做缓存——
 * 角色停用、权限调整在当次请求即生效。
 */
@Mapper
public interface RbacAuthorizationMapper {

    /** 账号当前仍启用的角色（按角色 id 升序，最宽范围归属取第一个，保证确定性）。 */
    @Select("SELECT r.id, r.role_code, r.role_name, r.status, r.remark, r.created_at, r.updated_at "
            + "FROM t_rbac_user_role ur JOIN t_rbac_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId} AND r.status = 'ACTIVE' "
            + "ORDER BY r.id ASC")
    List<RbacRole> selectActiveRolesByUser(@Param("userId") Long userId);

    /** 账号全部启用角色的权限点并集（DISTINCT 去重）。 */
    @Select("SELECT DISTINCT p.id, p.perm_code, p.perm_name, p.perm_type, p.module, p.created_at "
            + "FROM t_rbac_user_role ur "
            + "JOIN t_rbac_role r ON r.id = ur.role_id AND r.status = 'ACTIVE' "
            + "JOIN t_rbac_role_permission rp ON rp.role_id = ur.role_id "
            + "JOIN t_rbac_permission p ON p.id = rp.permission_id "
            + "WHERE ur.user_id = #{userId}")
    List<RbacPermission> selectPermissionsByUser(@Param("userId") Long userId);
}
