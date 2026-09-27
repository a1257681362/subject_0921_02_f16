package com.evops.apartment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.dto.CheckoutRequest;
import com.evops.apartment.dto.DeductionItemRequest;
import com.evops.apartment.entity.DepositDeduction;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.entity.LeaseCheckout;
import com.evops.apartment.mapper.DepositDeductionMapper;
import com.evops.apartment.mapper.LeaseCheckoutMapper;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.apartment.sensitive.SensitiveContext;
import com.evops.apartment.sensitive.SensitiveGuard;
import com.evops.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退租：验房结果、押金扣除明细、租约状态在同一事务内联动，失败整体回退。
 * 办理与查看均按当前身份最宽数据范围校验，并落数据放行审计。
 * 验房备注与扣除项名称/备注写入前过 DFA 敏感词。
 */
@Service
public class CheckoutService {
    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final LeaseMapper leaseMapper;
    private final LeaseCheckoutMapper checkoutMapper;
    private final DepositDeductionMapper deductionMapper;
    private final ApartmentScopeGuard scopeGuard;
    private final SensitiveGuard sensitiveGuard;

    public CheckoutService(LeaseMapper leaseMapper,
                           LeaseCheckoutMapper checkoutMapper,
                           DepositDeductionMapper deductionMapper,
                           ApartmentScopeGuard scopeGuard,
                           SensitiveGuard sensitiveGuard) {
        this.leaseMapper = leaseMapper;
        this.checkoutMapper = checkoutMapper;
        this.deductionMapper = deductionMapper;
        this.scopeGuard = scopeGuard;
        this.sensitiveGuard = sensitiveGuard;
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaseCheckout checkout(CheckoutRequest req) {
        try (TraceContext.Scope ignored = TraceContext.contractScope(req.getContractNo())) {
            return doCheckout(req);
        }
    }

    private LeaseCheckout doCheckout(CheckoutRequest req) {
        Lease lease = leaseMapper.selectOne(new QueryWrapper<Lease>()
                .eq("contract_no", req.getContractNo()));
        if (lease == null) {
            throw new IllegalArgumentException("租约不存在");
        }
        // 数据范围：SCOPE 校验房源所属片区，SELF 校验本人经办
        scopeGuard.requirePropertyCodeInScope(lease.getPropertyCode());
        scopeGuard.requireSelfOperated(lease.getOperator());
        if (!Lease.STATUS_ACTIVE.equals(lease.getStatus())) {
            throw new IllegalArgumentException("租约已退租");
        }

        List<DeductionItemRequest> items = req.getDeductions() == null
                ? Collections.emptyList() : req.getDeductions();
        BigDecimal deductTotal = BigDecimal.ZERO;
        for (DeductionItemRequest item : items) {
            deductTotal = deductTotal.add(item.getAmount());
        }
        if (deductTotal.compareTo(lease.getDeposit()) > 0) {
            throw new IllegalArgumentException("扣除总额不能超过押金");
        }

        String operator = scopeGuard.resolveOperator(req.getOperator());
        SensitiveContext ctx = SensitiveContext.builder(SensitiveContext.MODULE_CHECKOUT, operator)
                .bizRef(lease.getContractNo())
                .requestNo(req.getRequestNo())
                .build();

        LeaseCheckout checkout = new LeaseCheckout();
        checkout.setContractNo(lease.getContractNo());
        checkout.setCheckoutDate(req.getCheckoutDate());
        checkout.setInspectionResult(req.getInspectionResult());
        checkout.setInspectionRemark(
                sensitiveGuard.checkField(ctx, "inspectionRemark", req.getInspectionRemark()));
        checkout.setDeductTotal(deductTotal);
        checkout.setRefundAmount(lease.getDeposit().subtract(deductTotal));
        Audits.fillForInsert(checkout, req.getRequestNo(), operator, req.getBizTime());
        checkoutMapper.insert(checkout);

        // 押金扣除项逐条留痕；名称与备注逐字段过 DFA
        int idx = 0;
        for (DeductionItemRequest item : items) {
            DepositDeduction deduction = new DepositDeduction();
            deduction.setCheckoutId(checkout.getId());
            deduction.setItem(sensitiveGuard.checkField(ctx, "deductions[" + idx + "].item", item.getItem()));
            deduction.setAmount(item.getAmount());
            deduction.setRemark(sensitiveGuard.checkField(ctx, "deductions[" + idx + "].remark", item.getRemark()));
            deductionMapper.insert(deduction);
            idx++;
        }

        lease.setStatus(Lease.STATUS_CLOSED);
        lease.setDataVersion(lease.getDataVersion() + 1);
        lease.setUpdatedAt(LocalDateTime.now());
        leaseMapper.updateById(lease);
        log.info("退租办理成功 contractNo={} propertyCode={} deductTotal={} refundAmount={} requestNo={}",
                lease.getContractNo(), lease.getPropertyCode(), deductTotal, checkout.getRefundAmount(),
                req.getRequestNo());
        scopeGuard.audit("apartment:checkout:handle", req.getRequestNo(),
                "contractNo=" + lease.getContractNo() + ",propertyCode=" + lease.getPropertyCode(), null);
        return checkout;
    }

    public Map<String, Object> detail(String contractNo) {
        try (TraceContext.Scope ignored = TraceContext.contractScope(contractNo)) {
            LeaseCheckout checkout = checkoutMapper.selectOne(new QueryWrapper<LeaseCheckout>()
                    .eq("contract_no", contractNo));
            if (checkout == null) {
                throw new IllegalArgumentException("退租记录不存在");
            }
            Lease lease = leaseMapper.selectOne(new QueryWrapper<Lease>().eq("contract_no", contractNo));
            if (lease != null) {
                scopeGuard.requirePropertyCodeInScope(lease.getPropertyCode());
                scopeGuard.requireSelfOperated(lease.getOperator());
            }
            List<DepositDeduction> deductions = deductionMapper.selectList(new QueryWrapper<DepositDeduction>()
                    .eq("checkout_id", checkout.getId())
                    .orderByAsc("id"));
            scopeGuard.audit("apartment:checkout:view", null, "contractNo=" + contractNo, 1);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("checkout", checkout);
            result.put("deductions", deductions);
            return result;
        }
    }
}
