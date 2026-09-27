package com.evops.apartment.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 小区出租率额度账：一个小区一行。
 * available（余量）= total_amount - occupied_amount - reserved_amount，不落库、由服务层计算。
 */
@TableName("t_quota_balance")
public class QuotaBalance {
    public static final int RESERVE_AMOUNT = 1;

    private Long id;
    private String community;
    private Integer totalAmount;
    private Integer occupiedAmount;
    private Integer reservedAmount;
    private String requestNo;
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;
    private Integer dataVersion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    /** 余量：配额 - 已实扣 - 预占中。 */
    public int available() {
        return totalAmount - occupiedAmount - reservedAmount;
    }

    /** JSON 出口字段：余量随额度账一并返回。 */
    public int getAvailable() { return available(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public Integer getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Integer totalAmount) { this.totalAmount = totalAmount; }
    public Integer getOccupiedAmount() { return occupiedAmount; }
    public void setOccupiedAmount(Integer occupiedAmount) { this.occupiedAmount = occupiedAmount; }
    public Integer getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(Integer reservedAmount) { this.reservedAmount = reservedAmount; }
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
