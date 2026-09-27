package com.evops.rbac.core;

/**
 * 数据范围等级，顺序即宽度：SELF（仅本人经办） &lt; SCOPE（指定片区） &lt; ALL（全部）。
 * 多角色取并集时按 ordinal 最大者放行。
 */
public enum DataScopeLevel {
    SELF,
    SCOPE,
    ALL;

    public boolean widerThan(DataScopeLevel other) {
        return this.ordinal() > other.ordinal();
    }

    public static DataScopeLevel widest(Iterable<DataScopeLevel> levels) {
        DataScopeLevel widest = SELF;
        for (DataScopeLevel level : levels) {
            if (level != null && level.widerThan(widest)) {
                widest = level;
            }
        }
        return widest;
    }
}
