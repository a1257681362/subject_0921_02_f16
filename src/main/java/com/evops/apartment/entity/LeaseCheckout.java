package com.evops.apartment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 退租记录：登记验房结果与押金结算汇总，扣除明细见 t_deposit_deduction。
 */
@TableName("t_lease_checkout")
public class LeaseCheckout extends AuditEntity {
    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_DAMAGE = "DAMAGE";

    private String contractNo;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate checkoutDate;
    private String inspectionResult;
    private String inspectionRemark;
    private BigDecimal deductTotal;
    private BigDecimal refundAmount;

    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public LocalDate getCheckoutDate() { return checkoutDate; }
    public void setCheckoutDate(LocalDate checkoutDate) { this.checkoutDate = checkoutDate; }
    public String getInspectionResult() { return inspectionResult; }
    public void setInspectionResult(String inspectionResult) { this.inspectionResult = inspectionResult; }
    public String getInspectionRemark() { return inspectionRemark; }
    public void setInspectionRemark(String inspectionRemark) { this.inspectionRemark = inspectionRemark; }
    public BigDecimal getDeductTotal() { return deductTotal; }
    public void setDeductTotal(BigDecimal deductTotal) { this.deductTotal = deductTotal; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
}
