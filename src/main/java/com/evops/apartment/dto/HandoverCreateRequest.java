package com.evops.apartment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交接班登记请求（交班）。
 */
public class HandoverCreateRequest {
    @NotBlank(message = "小区不能为空")
    private String community;
    @NotBlank(message = "楼栋不能为空")
    private String building;
    @NotBlank(message = "班次不能为空")
    private String shift;                          // MORNING / EVENING
    @NotNull(message = "交接日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate shiftDate;
    private String pendingItems;                  // 待办事项
    private Integer keysCardCount;                // 钥匙/门卡数量
    private BigDecimal cashAmount;                // 备用金
    @NotBlank(message = "交接内容不能为空")
    private String content;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;

    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building; }
    public String getShift() { return shift; }
    public void setShift(String shift) { this.shift = shift; }
    public LocalDate getShiftDate() { return shiftDate; }
    public void setShiftDate(LocalDate shiftDate) { this.shiftDate = shiftDate; }
    public String getPendingItems() { return pendingItems; }
    public void setPendingItems(String pendingItems) { this.pendingItems = pendingItems; }
    public Integer getKeysCardCount() { return keysCardCount; }
    public void setKeysCardCount(Integer keysCardCount) { this.keysCardCount = keysCardCount; }
    public BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(BigDecimal cashAmount) { this.cashAmount = cashAmount; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
}
