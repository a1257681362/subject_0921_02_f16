package com.evops.apartment.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 任务 payload 的内存形态：字段与 {@link SignArchiveSubmitRequest} 一致，
 * remark 为提交时敏感词处置后的落库文本。
 */
public class SignArchivePayload {
    private String contractNo;
    private String propertyCode;
    private String tenantName;
    private String tenantPhone;
    private String tenantIdCard;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseStart;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseEnd;
    private BigDecimal monthlyRent;
    private BigDecimal deposit;
    private String remark;

    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getPropertyCode() { return propertyCode; }
    public void setPropertyCode(String propertyCode) { this.propertyCode = propertyCode; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getTenantPhone() { return tenantPhone; }
    public void setTenantPhone(String tenantPhone) { this.tenantPhone = tenantPhone; }
    public String getTenantIdCard() { return tenantIdCard; }
    public void setTenantIdCard(String tenantIdCard) { this.tenantIdCard = tenantIdCard; }
    public LocalDate getLeaseStart() { return leaseStart; }
    public void setLeaseStart(LocalDate leaseStart) { this.leaseStart = leaseStart; }
    public LocalDate getLeaseEnd() { return leaseEnd; }
    public void setLeaseEnd(LocalDate leaseEnd) { this.leaseEnd = leaseEnd; }
    public BigDecimal getMonthlyRent() { return monthlyRent; }
    public void setMonthlyRent(BigDecimal monthlyRent) { this.monthlyRent = monthlyRent; }
    public BigDecimal getDeposit() { return deposit; }
    public void setDeposit(BigDecimal deposit) { this.deposit = deposit; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
