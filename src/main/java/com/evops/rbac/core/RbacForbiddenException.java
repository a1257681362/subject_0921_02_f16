package com.evops.rbac.core;

/**
 * 鉴权拒绝：功能权限不足或数据范围越界，映射 HTTP 403。
 * 与未认证（401，令牌缺失/失效/账号停用）区分。
 */
public class RbacForbiddenException extends RuntimeException {
    public RbacForbiddenException(String message) {
        super(message);
    }
}
