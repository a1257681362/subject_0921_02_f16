package com.evops.apartment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.dto.LeaseCreateRequest;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.pinyin.LeasePinyinQuery;
import com.evops.apartment.pinyin.PinyinIndex;
import com.evops.apartment.pinyin.PinyinIndexer;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.apartment.sensitive.SensitiveContext;
import com.evops.apartment.sensitive.SensitiveGuard;
import com.evops.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 租约：建档依赖合同号唯一约束，并发同号只成功一条。
 * 检索按当前身份最宽数据范围过滤，并逐请求落数据放行审计。
 * 建档时租客姓名/手机/证件号之外的备注文本过 DFA 敏感词（结构化信息不扫，避免误伤）。
 */
@Service
public class LeaseService {
    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final LeaseMapper leaseMapper;
    private final ApartmentScopeGuard scopeGuard;
    private final SensitiveGuard sensitiveGuard;
    private final LeasePinyinQuery pinyinQuery;

    public LeaseService(LeaseMapper leaseMapper, ApartmentScopeGuard scopeGuard,
                        SensitiveGuard sensitiveGuard, LeasePinyinQuery pinyinQuery) {
        this.leaseMapper = leaseMapper;
        this.scopeGuard = scopeGuard;
        this.sensitiveGuard = sensitiveGuard;
        this.pinyinQuery = pinyinQuery;
    }

    @Transactional(rollbackFor = Exception.class)
    public Lease create(LeaseCreateRequest req) {
        // 合同号先入日志上下文：范围校验失败/唯一约束冲突等分支的日志也能按合同号聚合
        String previousContract = MDC.get(TraceContext.CONTRACT_NO);
        TraceContext.bindContract(req.getContractNo());
        try {
            scopeGuard.requirePropertyCodeInScope(req.getPropertyCode());
            String operator = scopeGuard.resolveOperator(req.getOperator());
            SensitiveContext ctx = SensitiveContext.builder(SensitiveContext.MODULE_LEASE, operator)
                    .bizRef(req.getContractNo())
                    .requestNo(req.getRequestNo())
                    .build();
            Lease lease = new Lease();
            lease.setContractNo(req.getContractNo());
            lease.setPropertyCode(req.getPropertyCode());
            lease.setTenantName(req.getTenantName());
            lease.setTenantPhone(req.getTenantPhone());
            lease.setTenantIdCard(req.getTenantIdCard());
            lease.setEmergencyContact(req.getEmergencyContact());
            lease.setLeaseStart(req.getLeaseStart());
            lease.setLeaseEnd(req.getLeaseEnd());
            lease.setMonthlyRent(req.getMonthlyRent());
            lease.setDeposit(req.getDeposit());
            lease.setRemark(sensitiveGuard.checkField(ctx, "remark", req.getRemark()));
            lease.setStatus(Lease.STATUS_ACTIVE);
            // 拼音冗余索引与租约行同一事务生成落库：多音字取多读音集合，检索口径见 search(keyword,...)
            PinyinIndex pinyin = PinyinIndexer.build(req.getTenantName());
            lease.setTenantNamePyFull(pinyin.getFull());
            lease.setTenantNamePyInitials(pinyin.getInitials());
            // Web 请求操作人以登录账号为准，请求体传入值仅在无登录上下文（任务/单测）时兜底
            Audits.fillForInsert(lease, req.getRequestNo(), operator, req.getBizTime());
            // uk_lease_contract_no 兜底：同合同号并发建档，仅一笔落库成功
            leaseMapper.insert(lease);
            log.info("租约建档成功 contractNo={} propertyCode={} leaseId={} requestNo={}",
                    lease.getContractNo(), lease.getPropertyCode(), lease.getId(), req.getRequestNo());
            scopeGuard.audit("apartment:lease:create", req.getRequestNo(),
                    "contractNo=" + req.getContractNo() + ",propertyCode=" + req.getPropertyCode(), null);
            return lease;
        } finally {
            restoreContract(previousContract);
        }
    }

    /**
     * 组合检索：合同号精确匹配 + 起租日期范围 + 租客关键字（apt-11），条件均可选；
     * 再叠加当前身份最宽数据范围（SELF/SCOPE/ALL）。
     *
     * <p>关键字三路匹配（OR）：中文原文 LIKE（tenant_name，含姓名中的字母数字片段）、
     * 全拼冗余字段、首字母冗余字段；关键字含汉字时只走中文原文（不拿汉字去拼音字段比），
     * 纯字母/数字时走全拼/首字母两个冗余字段。检索词先经 {@link PinyinIndexer#normalizeKeyword}
     * 做全半角（NFKC）与大小写折叠，全角输入、大写输入均容错。
     */
    public List<Lease> search(String contractNo, LocalDate startFrom, LocalDate startTo, String keyword) {
        return search(contractNo, startFrom, startTo, keyword, "apartment:lease:view");
    }

    /** 供导出复用：审计资源名区分检索与导出，查询口径保持完全一致。 */
    public List<Lease> search(String contractNo, LocalDate startFrom, LocalDate startTo, String keyword,
                              String auditResource) {
        QueryWrapper<Lease> query = new QueryWrapper<>();
        query.eq(StringUtils.hasText(contractNo), "contract_no", contractNo);
        query.ge(startFrom != null, "lease_start", startFrom);
        query.le(startTo != null, "lease_start", startTo);
        pinyinQuery.applyKeyword(query, keyword);
        scopeGuard.applyLeaseScope(query);
        query.orderByDesc("id");
        List<Lease> leases = leaseMapper.selectList(query);
        StringBuilder params = new StringBuilder();
        if (StringUtils.hasText(contractNo)) {
            params.append("contractNo=").append(contractNo);
        }
        if (startFrom != null) {
            params.append(params.length() > 0 ? "," : "").append("startFrom=").append(startFrom);
        }
        if (startTo != null) {
            params.append(params.length() > 0 ? "," : "").append("startTo=").append(startTo);
        }
        if (StringUtils.hasText(keyword)) {
            // 审计摘要记原始输入，方便回放“用户当时输了什么”
            params.append(params.length() > 0 ? "," : "").append("keyword=").append(keyword.trim());
        }
        scopeGuard.audit(auditResource, null, params.toString(), leases.size());
        return leases;
    }

    private static void restoreContract(String previous) {
        if (previous == null) {
            TraceContext.clearContract();
        } else {
            TraceContext.bindContract(previous);
        }
    }
}
