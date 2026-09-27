package com.evops.apartment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 交接班确认请求（接班）。接班人以登录账号为准。
 */
public class HandoverConfirmRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;

    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
}
