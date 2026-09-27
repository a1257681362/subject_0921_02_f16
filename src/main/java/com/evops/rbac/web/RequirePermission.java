package com.evops.rbac.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口所需权限点；多值为 AND 关系。
 * 一个账号兼多个角色时，权限点取全部启用角色的并集，命中其一即放行。
 * 鉴权关闭（evops.rbac.enabled=false）时该注解不生效，仅用于应急回退。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String[] value();
}
