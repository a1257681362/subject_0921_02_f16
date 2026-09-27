package com.evops.rbac.core;

/**
 * 未认证：令牌缺失/失效/过期，或账号已停用，映射 HTTP 401。
 */
public class RbacUnauthorizedException extends RuntimeException {
    public RbacUnauthorizedException(String message) {
        super(message);
    }
}
