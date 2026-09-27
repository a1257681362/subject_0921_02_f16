package com.evops.rbac.core;

import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 一次请求生效的最宽数据范围：多角色并集后的结论。
 * level 为 SCOPE 时，communities 为全部 SCOPE 角色授权小区的并集；
 * grantedBy 为提供该最宽等级的来源（角色编码，或用户级直授 "USER"），随审计落库。
 */
public class EffectiveScope {
    private final DataScopeLevel level;
    private final SortedSet<String> communities;
    private final SortedSet<String> grantedBy;

    public EffectiveScope(DataScopeLevel level, SortedSet<String> communities, SortedSet<String> grantedBy) {
        this.level = level == null ? DataScopeLevel.SELF : level;
        this.communities = communities == null ? new TreeSet<>() : communities;
        this.grantedBy = grantedBy == null ? new TreeSet<>() : grantedBy;
    }

    public static EffectiveScope self() {
        return new EffectiveScope(DataScopeLevel.SELF, new TreeSet<>(), new TreeSet<>());
    }

    public DataScopeLevel getLevel() { return level; }
    public SortedSet<String> getCommunities() { return Collections.unmodifiableSortedSet(communities); }
    public SortedSet<String> getGrantedBy() { return Collections.unmodifiableSortedSet(grantedBy); }

    public boolean isAll() { return level == DataScopeLevel.ALL; }
    public boolean isScope() { return level == DataScopeLevel.SCOPE; }
    public boolean isSelf() { return level == DataScopeLevel.SELF; }

    public String getScopeValue() { return String.join(",", communities); }
    public String getGrantedByText() { return String.join(",", grantedBy); }
}
