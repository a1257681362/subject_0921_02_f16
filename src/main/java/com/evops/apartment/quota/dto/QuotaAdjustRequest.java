package com.evops.apartment.quota.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 出租率配额调整：仅主管/管理员。
 * 下调不得击穿在途单（新配额 &lt; 已实扣 + 预占中 时拒绝）；调整全程落流水留痕。
 * 小区首次配置额度也走本接口（开户）。
 */
public class QuotaAdjustRequest {
    @NotBlank(message = "小区不能为空")
    private String community;
    @NotNull(message = "新配额不能为空")
    @Min(value = 0, message = "配额不能为负数")
    private Integer newTotalAmount;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public Integer getNewTotalAmount() { return newTotalAmount; }
    public void setNewTotalAmount(Integer newTotalAmount) { this.newTotalAmount = newTotalAmount; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
