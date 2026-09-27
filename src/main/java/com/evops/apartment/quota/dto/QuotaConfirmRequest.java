package com.evops.apartment.quota.dto;

import javax.validation.constraints.NotBlank;

/**
 * 预占确认实扣请求：凭预占单号确认，预占额转为已实扣。
 * holdNo 来自路径变量（{@code POST /holds/{holdNo}/confirm}），由控制器回填，不在请求体内必填。
 */
public class QuotaConfirmRequest {
    private String holdNo;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

    public String getHoldNo() { return holdNo; }
    public void setHoldNo(String holdNo) { this.holdNo = holdNo; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
