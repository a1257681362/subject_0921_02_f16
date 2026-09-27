package com.evops.rbac.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * evops.rbac：鉴权总开关与令牌有效期。enabled=false 为应急回退开关，
 * 关闭后公寓接口恢复 apt-2 的匿名可达行为（仅限紧急处置，需按上线说明回滚）。
 */
@ConfigurationProperties(prefix = "evops.rbac")
public class RbacProperties {
    private boolean enabled = true;
    private int tokenTtlHours = 12;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTokenTtlHours() { return tokenTtlHours; }
    public void setTokenTtlHours(int tokenTtlHours) { this.tokenTtlHours = tokenTtlHours; }
}
