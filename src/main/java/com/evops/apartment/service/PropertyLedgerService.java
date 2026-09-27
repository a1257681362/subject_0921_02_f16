package com.evops.apartment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.dto.PropertyEntryCreateRequest;
import com.evops.apartment.dto.PropertyReverseRequest;
import com.evops.apartment.entity.PropertyLedger;
import com.evops.apartment.mapper.PropertyLedgerMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.apartment.sensitive.SensitiveContext;
import com.evops.apartment.sensitive.SensitiveGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 房源台账：只增不改，更正通过冲销单完成。
 * 登记/冲销/流水均按当前身份最宽数据范围校验或过滤，并落数据放行审计。
 * 地址（小区/楼栋/房号）与备注写入前过 DFA 敏感词：命中拦截级即拒，打码级遮蔽后落库并留痕。
 */
@Service
public class PropertyLedgerService {
    private final PropertyLedgerMapper ledgerMapper;
    private final ApartmentScopeGuard scopeGuard;
    private final SensitiveGuard sensitiveGuard;

    public PropertyLedgerService(PropertyLedgerMapper ledgerMapper,
                                 ApartmentScopeGuard scopeGuard,
                                 SensitiveGuard sensitiveGuard) {
        this.ledgerMapper = ledgerMapper;
        this.scopeGuard = scopeGuard;
        this.sensitiveGuard = sensitiveGuard;
    }

    @Transactional(rollbackFor = Exception.class)
    public PropertyLedger register(PropertyEntryCreateRequest req) {
        scopeGuard.requireCommunityInScope(req.getCommunity());
        String operator = scopeGuard.resolveOperator(req.getOperator());
        SensitiveContext ctx = SensitiveContext.builder(SensitiveContext.MODULE_PROPERTY, operator)
                .bizRef(req.getPropertyCode())
                .requestNo(req.getRequestNo())
                .build();
        PropertyLedger entry = new PropertyLedger();
        entry.setPropertyCode(req.getPropertyCode());
        // 地址三字段与备注逐字段扫描：命中位置可定位到具体字段与字位
        entry.setCommunity(sensitiveGuard.checkField(ctx, "address.community", req.getCommunity()));
        entry.setBuilding(sensitiveGuard.checkField(ctx, "address.building", req.getBuilding()));
        entry.setRoomNo(sensitiveGuard.checkField(ctx, "address.roomNo", req.getRoomNo()));
        entry.setArea(req.getArea());
        entry.setRemark(sensitiveGuard.checkField(ctx, "remark", req.getRemark()));
        entry.setEntryType(PropertyLedger.TYPE_REGISTER);
        Audits.fillForInsert(entry, req.getRequestNo(), operator, req.getBizTime());
        ledgerMapper.insert(entry);
        scopeGuard.audit("apartment:property:register", req.getRequestNo(),
                "propertyCode=" + req.getPropertyCode() + ",community=" + req.getCommunity(), null);
        return entry;
    }

    /**
     * 冲销：原单不动，追加一条 REVERSE 流水指向原单；同一原单只允许冲销一次。
     */
    @Transactional(rollbackFor = Exception.class)
    public PropertyLedger reverse(PropertyReverseRequest req) {
        PropertyLedger original = ledgerMapper.selectById(req.getRefEntryId());
        if (original == null) {
            throw new IllegalArgumentException("原台账记录不存在");
        }
        scopeGuard.requireCommunityInScope(original.getCommunity());
        scopeGuard.requireSelfOperated(original.getOperator());
        if (!PropertyLedger.TYPE_REGISTER.equals(original.getEntryType())) {
            throw new IllegalArgumentException("仅登记单可冲销");
        }
        Long reversed = ledgerMapper.selectCount(new QueryWrapper<PropertyLedger>()
                .eq("ref_entry_id", original.getId())
                .eq("entry_type", PropertyLedger.TYPE_REVERSE));
        if (reversed != null && reversed > 0) {
            throw new IllegalArgumentException("该台账记录已冲销");
        }

        PropertyLedger reversal = new PropertyLedger();
        reversal.setPropertyCode(original.getPropertyCode());
        reversal.setCommunity(original.getCommunity());
        reversal.setBuilding(original.getBuilding());
        reversal.setRoomNo(original.getRoomNo());
        reversal.setArea(original.getArea());
        reversal.setEntryType(PropertyLedger.TYPE_REVERSE);
        reversal.setRefEntryId(original.getId());
        String reverseOperator = scopeGuard.resolveOperator(req.getOperator());
        SensitiveContext ctx = SensitiveContext.builder(SensitiveContext.MODULE_PROPERTY, reverseOperator)
                .bizRef(original.getPropertyCode())
                .requestNo(req.getRequestNo())
                .build();
        reversal.setRemark(sensitiveGuard.checkField(ctx, "reverseReason", req.getReason()));
        Audits.fillForInsert(reversal, req.getRequestNo(), reverseOperator, req.getBizTime());
        ledgerMapper.insert(reversal);
        scopeGuard.audit("apartment:property:reverse", req.getRequestNo(),
                "refEntryId=" + original.getId() + ",propertyCode=" + original.getPropertyCode(), null);
        return reversal;
    }

    public List<PropertyLedger> list(String propertyCode) {
        QueryWrapper<PropertyLedger> query = new QueryWrapper<>();
        query.eq(StringUtils.hasText(propertyCode), "property_code", propertyCode);
        scopeGuard.applyLedgerScope(query);
        query.orderByAsc("id");
        List<PropertyLedger> entries = ledgerMapper.selectList(query);
        scopeGuard.audit("apartment:property:view", null,
                StringUtils.hasText(propertyCode) ? "propertyCode=" + propertyCode : "", entries.size());
        return entries;
    }
}
