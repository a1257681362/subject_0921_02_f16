package com.evops.apartment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退租请求：登记验房结果，押金扣除项逐条随单落库。
 */
public class CheckoutRequest {
    @NotBlank(message = "合同号不能为空")
    private String contractNo;
    @NotNull(message = "退租日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate checkoutDate;
    @NotBlank(message = "验房结果不能为空")
    private String inspectionResult;
    private String inspectionRemark;
    @Valid
    private List<DeductionItemRequest> deductions;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;

    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public LocalDate getCheckoutDate() { return checkoutDate; }
    public void setCheckoutDate(LocalDate checkoutDate) { this.checkoutDate = checkoutDate; }
    public String getInspectionResult() { return inspectionResult; }
    public void setInspectionResult(String inspectionResult) { this.inspectionResult = inspectionResult; }
    public String getInspectionRemark() { return inspectionRemark; }
    public void setInspectionRemark(String inspectionRemark) { this.inspectionRemark = inspectionRemark; }
    public List<DeductionItemRequest> getDeductions() { return deductions; }
    public void setDeductions(List<DeductionItemRequest> deductions) { this.deductions = deductions; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
}
