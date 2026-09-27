package com.evops.apartment.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 额度预占单：申请先占（HELD）→ 确认实扣（CONFIRMED）／释放（RELEASED）。
 * 状态单向流转，只增不删。同单（biz_ref）同时只允许一笔活跃单——
 * 由库内生成列 active_biz_ref 上的唯一索引兜底（见 schema.sql）。
 */
@TableName("t_quota_hold")
public class QuotaHold {
    public static final String STATUS_HELD = "HELD";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_RELEASED = "RELEASED";

    public static final String REASON_CANCEL = "CANCEL";
    public static final String REASON_TIMEOUT = "TIMEOUT";

    private Long id;
    private String holdNo;
    private String community;
    /** 业务幂等键：本期为租约合同号。 */
    private String bizRef;
    private Integer reserveAmount;
    private String status;
    private String releaseReason;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiresAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime confirmedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime releasedAt;
    private String requestNo;
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;
    private Integer dataVersion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public boolean held() { return STATUS_HELD.equals(status); }
    public boolean expired(LocalDateTime now) { return expiresAt != null && !expiresAt.isAfter(now); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getHoldNo() { return holdNo; }
    public void setHoldNo(String holdNo) { this.holdNo = holdNo; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getBizRef() { return bizRef; }
    public void setBizRef(String bizRef) { this.bizRef = bizRef; }
    public Integer getReserveAmount() { return reserveAmount; }
    public void setReserveAmount(Integer reserveAmount) { this.reserveAmount = reserveAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReleaseReason() { return releaseReason; }
    public void setReleaseReason(String releaseReason) { this.releaseReason = releaseReason; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
    public Integer getDataVersion() { return dataVersion; }
    public void setDataVersion(Integer dataVersion) { this.dataVersion = dataVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
