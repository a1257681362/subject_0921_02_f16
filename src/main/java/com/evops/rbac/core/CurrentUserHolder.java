package com.evops.rbac.core;

/**
 * 当前身份的请求级线程上下文。鉴权过滤器在请求开始时写入、结束时清理，
 * Service 层据此做数据范围过滤与审计，不经过方法传参。
 */
public final class CurrentUserHolder {
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
