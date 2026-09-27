package com.evops.apartment.quota.dto;

import javax.validation.constraints.NotBlank;

/**
 * 预占取消请求：主动释放预占额；与后台超时释放互斥（CAS 保证只释放一次）。
 * holdNo 来自路径变量（{@code POST /holds/{holdNo}/cancel}），由控制器回填，不在请求体内必填。
 */
public class QuotaCancelRequest {
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
