package com.evops.apartment.service;

import com.evops.apartment.entity.AuditEntity;

import java.time.LocalDateTime;

/**
 * 写入时统一落审计字段：请求号、操作人、业务时间、数据版本。
 */
public final class Audits {
    private Audits() {
    }

    public static void fillForInsert(AuditEntity entity, String requestNo, String operator, LocalDateTime bizTime) {
        entity.setRequestNo(requestNo);
        entity.setOperator(operator);
        entity.setBizTime(bizTime == null ? LocalDateTime.now() : bizTime);
        entity.setDataVersion(1);
    }
}
