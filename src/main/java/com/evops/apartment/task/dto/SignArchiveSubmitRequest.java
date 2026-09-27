package com.evops.apartment.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 签约归档任务入参（随任务落 payload，断点重跑不依赖请求现场）。
 * 字段口径与租约建档一致；敏感词在提交时由请求线程同步扫描（打码/拦截即时反馈），
 * 落库的是处置后的文本。
 */
public class SignArchiveSubmitRequest {
    @NotBlank(message = "合同号不能为空")
    private String contractNo;
    @NotBlank(message = "房源编号不能为空")
    private String propertyCode;
    @NotBlank(message = "租客姓名不能为空")
    private String tenantName;
    private String tenantPhone;
    private String tenantIdCard;
    @NotNull(message = "起租日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseStart;
    @NotNull(message = "到期日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseEnd;
    @NotNull(message = "月租金不能为空")
    private BigDecimal monthlyRent;
    @NotNull(message = "押金不能为空")
    private BigDecimal deposit;
    private String remark;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

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
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
