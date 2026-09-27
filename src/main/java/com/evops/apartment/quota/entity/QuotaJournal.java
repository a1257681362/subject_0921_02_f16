package com.evops.apartment.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 额度流水账：每一次余额/配额变化一条，只增不改。
 * delta_total 仅 ADJUST 非 0；delta_occupied 仅 CONFIRM +1；
 * delta_reserved：RESERVE +1 / CONFIRM -1 / RELEASE_* -1。
 * *_after 为该变化提交后的账上余额，任何时点余量可按流水回放对账。
 */
@TableName("t_quota_journal")
public class QuotaJournal {
    public static final String TYPE_RESERVE = "RESERVE";
    public static final String TYPE_CONFIRM = "CONFIRM";
    public static final String TYPE_RELEASE_CANCEL = "RELEASE_CANCEL";
    public static final String TYPE_RELEASE_TIMEOUT = "RELEASE_TIMEOUT";
    public static final String TYPE_ADJUST = "ADJUST";

    private Long id;
    private String community;
    private String changeType;
    private Long holdId;
    private String holdNo;
    private String bizRef;
    private Integer deltaTotal;
    private Integer deltaOccupied;
    private Integer deltaReserved;
    private Integer totalAfter;
    private Integer occupiedAfter;
    private Integer reservedAfter;
    private String requestNo;
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public Long getHoldId() { return holdId; }
    public void setHoldId(Long holdId) { this.holdId = holdId; }
    public String getHoldNo() { return holdNo; }
    public void setHoldNo(String holdNo) { this.holdNo = holdNo; }
    public String getBizRef() { return bizRef; }
    public void setBizRef(String bizRef) { this.bizRef = bizRef; }
    public Integer getDeltaTotal() { return deltaTotal; }
    public void setDeltaTotal(Integer deltaTotal) { this.deltaTotal = deltaTotal; }
    public Integer getDeltaOccupied() { return deltaOccupied; }
    public void setDeltaOccupied(Integer deltaOccupied) { this.deltaOccupied = deltaOccupied; }
    public Integer getDeltaReserved() { return deltaReserved; }
    public void setDeltaReserved(Integer deltaReserved) { this.deltaReserved = deltaReserved; }
    public Integer getTotalAfter() { return totalAfter; }
    public void setTotalAfter(Integer totalAfter) { this.totalAfter = totalAfter; }
    public Integer getOccupiedAfter() { return occupiedAfter; }
    public void setOccupiedAfter(Integer occupiedAfter) { this.occupiedAfter = occupiedAfter; }
    public Integer getReservedAfter() { return reservedAfter; }
    public void setReservedAfter(Integer reservedAfter) { this.reservedAfter = reservedAfter; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
