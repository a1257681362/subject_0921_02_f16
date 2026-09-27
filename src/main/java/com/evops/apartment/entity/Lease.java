package com.evops.apartment.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.apartment.mask.Sensitive;
import com.evops.apartment.mask.SensitiveType;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租约全量记录。合同号全库唯一。
 */
@TableName(value = "t_lease", autoResultMap = true)
public class Lease extends AuditEntity {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    private String contractNo;
    private String propertyCode;
    @Sensitive(SensitiveType.NAME)
    private String tenantName;
    @Sensitive(SensitiveType.PHONE)
    private String tenantPhone;
    @Sensitive(SensitiveType.ID_CARD)
    private String tenantIdCard;
    /**
     * apt-12 紧急联系人（嵌套对象）：库内 JSON 明文存于 emergency_contact 列，
     * 出口（接口/导出/日志）按嵌套成员上的 @Sensitive 递归脱敏。
     */
    @TableField(value = "emergency_contact", typeHandler = EmergencyContactJsonTypeHandler.class)
    private EmergencyContact emergencyContact;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseStart;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseEnd;
    private BigDecimal monthlyRent;
    private BigDecimal deposit;
    private String remark;
    private String status;
    /**
     * apt-11 租客姓名拼音冗余索引（|变体1|变体2|，多音字多读音笛卡尔积）。
     * 仅供库内三路检索使用，不对外输出（姓名经 @Sensitive 脱敏，拼音字段同样不外泄）。
     */
    @JsonIgnore
    private String tenantNamePyFull;
    @JsonIgnore
    private String tenantNamePyInitials;
    /** apt-5 签约归档完成后回写的归档记录 id。 */
    private Long signArchiveId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTenantNamePyFull() { return tenantNamePyFull; }
    public void setTenantNamePyFull(String tenantNamePyFull) { this.tenantNamePyFull = tenantNamePyFull; }
    public String getTenantNamePyInitials() { return tenantNamePyInitials; }
    public void setTenantNamePyInitials(String tenantNamePyInitials) { this.tenantNamePyInitials = tenantNamePyInitials; }
    public Long getSignArchiveId() { return signArchiveId; }
    public void setSignArchiveId(Long signArchiveId) { this.signArchiveId = signArchiveId; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
