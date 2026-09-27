package com.evops.apartment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.dto.HandoverConfirmRequest;
import com.evops.apartment.dto.HandoverCreateRequest;
import com.evops.apartment.entity.HandoverRecord;
import com.evops.apartment.mapper.HandoverRecordMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.apartment.sensitive.SensitiveContext;
import com.evops.apartment.sensitive.SensitiveGuard;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.RbacForbiddenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 交接班：交班登记 → 接班人确认 → 按小区/楼栋/班次/日期/状态检索。
 * 替代纸质交接本：记录只增不删，状态 DRAFT → CONFIRMED 单向流转。
 * 登记文本（小区/楼栋/待办/内容）写入前过 DFA 敏感词；检索按数据范围过滤并落放行审计。
 */
@Service
public class HandoverService {
    private static final List<String> SHIFTS =
            Arrays.asList(HandoverRecord.SHIFT_MORNING, HandoverRecord.SHIFT_EVENING);

    private final HandoverRecordMapper handoverMapper;
    private final ApartmentScopeGuard scopeGuard;
    private final SensitiveGuard sensitiveGuard;

    public HandoverService(HandoverRecordMapper handoverMapper,
                           ApartmentScopeGuard scopeGuard,
                           SensitiveGuard sensitiveGuard) {
        this.handoverMapper = handoverMapper;
        this.scopeGuard = scopeGuard;
        this.sensitiveGuard = sensitiveGuard;
    }

    @Transactional(rollbackFor = Exception.class)
    public HandoverRecord create(HandoverCreateRequest req) {
        if (!SHIFTS.contains(req.getShift())) {
            throw new IllegalArgumentException("班次非法，仅支持 MORNING/EVENING");
        }
        scopeGuard.requireCommunityInScope(req.getCommunity());
        String operator = scopeGuard.resolveOperator(req.getOperator());
        SensitiveContext ctx = SensitiveContext.builder(SensitiveContext.MODULE_HANDOVER, operator)
                .bizRef(req.getCommunity() + "/" + req.getBuilding() + "/" + req.getShiftDate())
                .requestNo(req.getRequestNo())
                .build();

        HandoverRecord record = new HandoverRecord();
        record.setCommunity(sensitiveGuard.checkField(ctx, "address.community", req.getCommunity()));
        record.setBuilding(sensitiveGuard.checkField(ctx, "address.building", req.getBuilding()));
        record.setShift(req.getShift());
        record.setShiftDate(req.getShiftDate());
        record.setPendingItems(sensitiveGuard.checkField(ctx, "pendingItems", req.getPendingItems()));
        record.setKeysCardCount(req.getKeysCardCount());
        record.setCashAmount(req.getCashAmount());
        record.setContent(sensitiveGuard.checkField(ctx, "content", req.getContent()));
        record.setHandoverBy(operator);
        record.setStatus(HandoverRecord.STATUS_DRAFT);
        Audits.fillForInsert(record, req.getRequestNo(), operator, req.getBizTime());
        record.setUpdatedAt(LocalDateTime.now());
        handoverMapper.insert(record);

        scopeGuard.audit("apartment:handover:create", req.getRequestNo(),
                "community=" + req.getCommunity() + ",building=" + req.getBuilding()
                        + ",shiftDate=" + req.getShiftDate(), null);
        return record;
    }

    /**
     * 接班确认：仅 DRAFT 可确认，且不能自交自接；确认人以登录账号为准。
     */
    @Transactional(rollbackFor = Exception.class)
    public HandoverRecord confirm(Long id, HandoverConfirmRequest req) {
        HandoverRecord record = handoverMapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("交接班记录不存在");
        }
        if (!HandoverRecord.STATUS_DRAFT.equals(record.getStatus())) {
            throw new IllegalArgumentException("该交接班记录已确认，不能重复确认");
        }
        // 数据范围：SCOPE 校验小区；SELF 不限制（接班人本就可能是另一位管家）
        scopeGuard.requireCommunityInScope(record.getCommunity());
        String operator = scopeGuard.resolveOperator(req.getOperator());
        if (operator.equals(record.getHandoverBy())) {
            throw new IllegalArgumentException("交班人与接班人不能为同一人");
        }

        record.setTakeOverBy(operator);
        record.setStatus(HandoverRecord.STATUS_CONFIRMED);
        record.setConfirmedAt(LocalDateTime.now());
        record.setConfirmRequestNo(req.getRequestNo());
        record.setDataVersion(record.getDataVersion() + 1);
        record.setUpdatedAt(LocalDateTime.now());
        handoverMapper.updateById(record);
        scopeGuard.audit("apartment:handover:confirm", req.getRequestNo(),
                "handoverId=" + id, null);
        return record;
    }

    /** 组合检索：小区/楼栋/班次/日期区间/状态均可选，叠加数据范围。 */
    public List<HandoverRecord> search(String community, String building, String shift,
                                       LocalDate dateFrom, LocalDate dateTo, String status) {
        QueryWrapper<HandoverRecord> query = new QueryWrapper<>();
        query.eq(StringUtils.hasText(community), "community", community);
        query.eq(StringUtils.hasText(building), "building", building);
        query.eq(StringUtils.hasText(shift), "shift", shift);
        query.eq(StringUtils.hasText(status), "status", status);
        query.ge(dateFrom != null, "shift_date", dateFrom);
        query.le(dateTo != null, "shift_date", dateTo);
        scopeGuard.applyHandoverScope(query);
        query.orderByDesc("shift_date").orderByDesc("id");
        List<HandoverRecord> records = handoverMapper.selectList(query);
        StringBuilder params = new StringBuilder();
        appendParam(params, "community", community);
        appendParam(params, "building", building);
        appendParam(params, "shift", shift);
        appendParam(params, "status", status);
        if (dateFrom != null) {
            appendParam(params, "dateFrom", dateFrom.toString());
        }
        if (dateTo != null) {
            appendParam(params, "dateTo", dateTo.toString());
        }
        scopeGuard.audit("apartment:handover:view", null, params.toString(), records.size());
        return records;
    }

    /** 详情：同样按数据范围放行，越权 403。 */
    public HandoverRecord detail(Long id) {
        HandoverRecord record = handoverMapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("交接班记录不存在");
        }
        CurrentUser user = CurrentUserHolder.get();
        if (user != null && !user.getScope().isAll()) {
            if (user.getScope().isSelf()) {
                if (!user.getUsername().equals(record.getOperator())
                        && !user.getUsername().equals(record.getTakeOverBy())) {
                    throw new RbacForbiddenException("仅可查看本人交班或接班的记录");
                }
            } else if (!user.getScope().getCommunities().contains(record.getCommunity())) {
                throw new RbacForbiddenException("小区不在授权片区范围内");
            }
        }
        scopeGuard.audit("apartment:handover:view", null, "handoverId=" + id, 1);
        return record;
    }

    private static void appendParam(StringBuilder params, String key, String value) {
        if (StringUtils.hasText(value)) {
            if (params.length() > 0) {
                params.append(",");
            }
            params.append(key).append("=").append(value);
        }
    }
}
