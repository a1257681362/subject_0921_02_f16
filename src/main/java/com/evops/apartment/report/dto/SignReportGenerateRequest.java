package com.evops.apartment.report.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 生成签约结果 Word 报告请求。报告维度为公寓楼（buildingName），
 * 起租日期区间可选；本期仅从新台账（REGISTER 流水）归集该楼房源与新签租约。
 */
public class SignReportGenerateRequest {
    @NotBlank(message = "公寓楼名称不能为空")
    private String buildingName;
    /** 所属小区；SCOPE 账号必须落在授权片区，不传则按楼栋在台账中的小区归集。 */
    private String community;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startFrom;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startTo;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

    public String getBuildingName() { return buildingName; }
    public void setBuildingName(String buildingName) { this.buildingName = buildingName; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public LocalDate getStartFrom() { return startFrom; }
    public void setStartFrom(LocalDate startFrom) { this.startFrom = startFrom; }
    public LocalDate getStartTo() { return startTo; }
    public void setStartTo(LocalDate startTo) { this.startTo = startTo; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
