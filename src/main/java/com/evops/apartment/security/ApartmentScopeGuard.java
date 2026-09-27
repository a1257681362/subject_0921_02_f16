package com.evops.apartment.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.entity.HandoverRecord;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.entity.PropertyLedger;
import com.evops.apartment.mapper.PropertyLedgerMapper;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.RbacForbiddenException;
import com.evops.rbac.service.DataScopeAuditService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 公寓模块数据范围守卫：读按最宽范围过滤、写按最宽范围校验，每一次放行落审计。
 * 范围口径：SELF 仅本人经办（operator=登录账号）；SCOPE 指定小区（按房源台账小区归集）；ALL 全部。
 * 无登录上下文的进程内调用（任务/既有单测）不施加范围限制；Web 请求经鉴权过滤器必有身份。
 */
@Component
public class ApartmentScopeGuard {
    private final PropertyLedgerMapper ledgerMapper;
    private final DataScopeAuditService auditService;

    public ApartmentScopeGuard(PropertyLedgerMapper ledgerMapper,
                               DataScopeAuditService auditService) {
        this.ledgerMapper = ledgerMapper;
        this.auditService = auditService;
    }

    public boolean enforced() {
        return CurrentUserHolder.get() != null;
    }

    /** 租约检索/导出：SCOPE 借台账把小区范围翻译成房源编号集合。 */
    public void applyLeaseScope(QueryWrapper<Lease> query) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null) {
            return;
        }
        if (user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            query.eq("operator", user.getUsername());
            return;
        }
        if (user.getScope().getCommunities().isEmpty()) {
            query.apply("1 = 0");
            return;
        }
        List<String> codes = scopedPropertyCodes(user);
        if (codes.isEmpty()) {
            query.apply("1 = 0");
        } else {
            query.in("property_code", codes);
        }
    }

    /** 台账流水：台账自带 community 列，直接按小区过滤。 */
    public void applyLedgerScope(QueryWrapper<PropertyLedger> query) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            query.eq("operator", user.getUsername());
            return;
        }
        if (user.getScope().getCommunities().isEmpty()) {
            query.apply("1 = 0");
        } else {
            query.in("community", new ArrayList<>(user.getScope().getCommunities()));
        }
    }

    /** 交接班检索：交接记录自带 community 列，口径与台账流水一致（SELF 看本人交班/接班）。 */
    public void applyHandoverScope(QueryWrapper<HandoverRecord> query) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            // 本人经办 = 本人交班或本人接班
            query.and(w -> w.eq("operator", user.getUsername())
                    .or().eq("take_over_by", user.getUsername()));
            return;
        }
        if (user.getScope().getCommunities().isEmpty()) {
            query.apply("1 = 0");
        } else {
            query.in("community", new ArrayList<>(user.getScope().getCommunities()));
        }
    }

    /** 写校验：建档前确认房源落在本人最宽范围内。 */
    public void requirePropertyCodeInScope(String propertyCode) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            return;
        }
        if (!scopedPropertyCodes(user).contains(propertyCode)) {
            throw new RbacForbiddenException("房源不在授权片区范围内");
        }
    }

    /** 写校验：台账登记/冲销的小区必须落在 SCOPE 授权内。 */
    public void requireCommunityInScope(String community) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll() || user.getScope().isSelf()) {
            return;
        }
        if (community == null || !user.getScope().getCommunities().contains(community)) {
            throw new RbacForbiddenException("小区不在授权片区范围内");
        }
    }

    /** 写校验：SELF 角色只能操作本人经办的台账/租约。 */
    public void requireSelfOperated(String operator) {
        CurrentUser user = CurrentUserHolder.get();
        if (user != null && user.getScope().isSelf() && !user.getUsername().equals(operator)) {
            throw new RbacForbiddenException("仅可操作本人经办的数据");
        }
    }

    /** Web 写入一律以登录账号为操作人；无登录上下文（单测/任务）沿用请求体传入值。 */
    public String resolveOperator(String requestOperator) {
        CurrentUser user = CurrentUserHolder.get();
        return user == null ? requestOperator : user.getUsername();
    }

    /** 数据放行审计：每一次按最宽角色放行的查询留痕；写操作行数传 null。 */
    public void audit(String resource, String requestNo, String queryParams, Integer resultCount) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null) {
            return;
        }
        auditService.record(user, resource, requestNo, queryParams, resultCount);
    }

    /** SCOPE 角色授权片区内的房源编号集合（apt-5 任务列表过滤复用同一口径）。 */
    public List<String> scopedPropertyCodes() {
        CurrentUser user = CurrentUserHolder.get();
        return user == null ? java.util.Collections.emptyList() : scopedPropertyCodes(user);
    }

    private List<String> scopedPropertyCodes(CurrentUser user) {
        List<PropertyLedger> rows = ledgerMapper.selectList(new QueryWrapper<PropertyLedger>()
                .select("DISTINCT property_code")
                .in("community", new ArrayList<>(user.getScope().getCommunities())));
        List<String> codes = new ArrayList<>();
        for (PropertyLedger row : rows) {
            if (row.getPropertyCode() != null && !codes.contains(row.getPropertyCode())) {
                codes.add(row.getPropertyCode());
            }
        }
        return codes;
    }
}
