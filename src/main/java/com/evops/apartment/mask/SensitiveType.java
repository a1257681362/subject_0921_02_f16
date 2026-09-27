package com.evops.apartment.mask;

/**
 * 脱敏字段类型：规则按类型集中配置，接口与导出共用同一份。
 */
public enum SensitiveType {
    /** 姓名：保留姓氏（长度 1 时整体遮蔽） */
    NAME,
    /** 手机号：保留前 3 后 4 */
    PHONE,
    /** 证件号：保留前 6 后 4 */
    ID_CARD
}
