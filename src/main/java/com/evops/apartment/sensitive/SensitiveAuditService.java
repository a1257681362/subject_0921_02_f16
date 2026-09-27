package com.evops.apartment.sensitive;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 敏感词命中痕迹落库。
 *
 * 关键：任何命中都以 REQUIRES_NEW 独立事务写入——
 * BLOCK 时业务事务随后回滚，痕迹必须保留；MASK 时即便后续业务步骤失败，提交尝试仍可追溯。
 */
@Service
public class SensitiveAuditService {
    private static final int EXCERPT_LIMIT = 255;

    private final SensitiveAuditMapper auditMapper;

    public SensitiveAuditService(SensitiveAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(SensitiveContext ctx, String fieldPath, String rawText,
                       MatchHit hit, String actionTaken, String maskedText) {
        SensitiveAudit audit = new SensitiveAudit();
        audit.setRequestNo(ctx.getRequestNo());
        audit.setUsername(ctx.getOperator());
        audit.setModule(ctx.getModule());
        audit.setBizRef(ctx.getBizRef());
        audit.setFieldPath(fieldPath);
        audit.setWordText(hit.getWord());
        audit.setCategory(hit.getCategory());
        audit.setActionTaken(actionTaken);
        audit.setRawExcerpt(excerpt(rawText, hit.getStart(), hit.getEnd()));
        audit.setStartIndex(hit.getStart());
        audit.setEndIndex(hit.getEnd());
        audit.setMaskedText(truncate(maskedText));
        audit.setSourceId(ctx.getSourceId());
        audit.setRequestTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    /** 痕迹检索（管理侧）：支持按账号/模块/业务定位/处置动作过滤。 */
    public List<SensitiveAudit> search(String username, String module, String bizRef,
                                       String actionTaken, Integer limit) {
        QueryWrapper<SensitiveAudit> query = new QueryWrapper<>();
        query.eq(username != null && !username.isEmpty(), "username", username);
        query.eq(module != null && !module.isEmpty(), "module", module);
        query.eq(bizRef != null && !bizRef.isEmpty(), "biz_ref", bizRef);
        query.eq(actionTaken != null && !actionTaken.isEmpty(), "action_taken", actionTaken);
        query.orderByDesc("id");
        query.last("LIMIT " + Math.min(Math.max(limit == null ? 100 : limit, 1), 500));
        return auditMapper.selectList(query);
    }

    private static String excerpt(String text, int start, int end) {
        if (text == null) {
            return null;
        }
        int from = Math.max(0, start - 10);
        int to = Math.min(text.length(), end + 10);
        String prefix = from > 0 ? "…" : "";
        String suffix = to < text.length() ? "…" : "";
        return truncate(prefix + text.substring(from, to) + suffix);
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= EXCERPT_LIMIT) {
            return text;
        }
        return text.substring(0, EXCERPT_LIMIT - 1) + "…";
    }
}
