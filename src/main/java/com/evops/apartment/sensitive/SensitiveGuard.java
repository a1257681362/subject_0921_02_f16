package com.evops.apartment.sensitive;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词扫描门面：业务写入链路统一入口。
 *
 * 行为：
 * - 无命中：原文返回；
 * - 命中 MASK(1)：逐词落命中痕迹（独立事务），返回打码文本，业务继续提交；
 * - 命中 BLOCK(2)：先把全部拦截命中落痕迹（独立事务，业务回滚不影响留痕），再抛
 *   {@link SensitiveBlockException}，字段与字位随异常返回，调用方事务回滚。
 */
@Component
public class SensitiveGuard {
    private final SensitiveWordRegistry registry;
    private final SensitiveAuditService auditService;

    public SensitiveGuard(SensitiveWordRegistry registry, SensitiveAuditService auditService) {
        this.registry = registry;
        this.auditService = auditService;
    }

    /**
     * 检查并处置单个字段。
     *
     * @return 可落库的字段值（有 MASK 命中时为打码值）
     * @throws SensitiveBlockException 命中拦截级词条
     */
    public String checkField(SensitiveContext ctx, String fieldPath, String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }
        ScanResult result = registry.scan(rawText);
        if (result.getHits().isEmpty()) {
            return rawText;
        }
        List<MatchHit> blockHits = new ArrayList<>();
        for (MatchHit hit : result.getHits()) {
            if (hit.getActionLevel() == WordAction.BLOCK) {
                blockHits.add(hit);
                auditService.record(ctx, fieldPath, rawText, hit,
                        SensitiveAudit.ACTION_BLOCK, null);
            } else {
                auditService.record(ctx, fieldPath, rawText, hit,
                        SensitiveAudit.ACTION_MASK, result.getMaskedText());
            }
        }
        if (!blockHits.isEmpty()) {
            throw new SensitiveBlockException(fieldPath, blockHits);
        }
        return result.getMaskedText();
    }
}
