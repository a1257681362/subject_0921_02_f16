package com.evops.apartment.quota.dto;

import javax.validation.constraints.NotBlank;

/**
 * 额度预占申请：按业务单（本期=租约合同号）对所在小区预占 1 个出租额度。
 * reserveAmount 固定为 1（一次租约建档对应一个出租名额），不由调用方指定，防多占。
 */
public class QuotaApplyRequest {
    @NotBlank(message = "小区不能为空")
    private String community;
    @NotBlank(message = "业务单号（合同号）不能为空")
    private String bizRef;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getBizRef() { return bizRef; }
    public void setBizRef(String bizRef) { this.bizRef = bizRef; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
