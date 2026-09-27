package com.evops.apartment.dto;

import com.evops.apartment.entity.EmergencyContact;
import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租约建档请求。合同号唯一，重复或并发同号仅一单成功。
 */
public class LeaseCreateRequest {
    @NotBlank(message = "合同号不能为空")
    private String contractNo;
    @NotBlank(message = "房源编号不能为空")
    private String propertyCode;
    @NotBlank(message = "租客姓名不能为空")
    private String tenantName;
    private String tenantPhone;
    private String tenantIdCard;
    /** apt-12 紧急联系人（嵌套对象，选填）：姓名/电话按 @Sensitive 同规则脱敏输出。 */
    @Valid
    private EmergencyContact emergencyContact;
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
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;

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
    public EmergencyContact getEmergencyContact() { return emergencyContact; }
    public void setEmergencyContact(EmergencyContact emergencyContact) { this.emergencyContact = emergencyContact; }
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
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
}
