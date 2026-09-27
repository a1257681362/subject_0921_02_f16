package com.evops.apartment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 房源台账冲销请求：对指定登记单开一张冲销单，原单不动。
 */
public class PropertyReverseRequest {
    @NotNull(message = "原台账id不能为空")
    private Long refEntryId;
    @NotBlank(message = "冲销原因不能为空")
    private String reason;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;

    public Long getRefEntryId() { return refEntryId; }
    public void setRefEntryId(Long refEntryId) { this.refEntryId = refEntryId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
}
