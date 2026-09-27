package com.evops.apartment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 业务表公共审计字段：请求号、操作人、业务时间、数据版本随写入一起落库。
 */
public abstract class AuditEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;
    private Integer dataVersion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
}
