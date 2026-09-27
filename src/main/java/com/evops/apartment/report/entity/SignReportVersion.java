package com.evops.apartment.report.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.apartment.entity.AuditEntity;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 签约结果 Word 报告版本登记。同一公寓楼每出一版 version_no +1，只增不改；
 * 文件落归档目录（file_path 唯一），历史报告不覆盖。
 */
@TableName("t_sign_report_version")
public class SignReportVersion extends AuditEntity {
    public static final String TYPE_SIGN_RESULT = "SIGN_RESULT";

    private String reportType;
    private String buildingName;
    private String community;
    private Integer versionNo;
    private String fileName;
    private String filePath;
    private Integer leaseCount;
    private BigDecimal rentTotal;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseStartFrom;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaseStartTo;

    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getBuildingName() { return buildingName; }
    public void setBuildingName(String buildingName) { this.buildingName = buildingName; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getLeaseCount() { return leaseCount; }
    public void setLeaseCount(Integer leaseCount) { this.leaseCount = leaseCount; }
    public BigDecimal getRentTotal() { return rentTotal; }
    public void setRentTotal(BigDecimal rentTotal) { this.rentTotal = rentTotal; }
    public LocalDate getLeaseStartFrom() { return leaseStartFrom; }
    public void setLeaseStartFrom(LocalDate leaseStartFrom) { this.leaseStartFrom = leaseStartFrom; }
    public LocalDate getLeaseStartTo() { return leaseStartTo; }
    public void setLeaseStartTo(LocalDate leaseStartTo) { this.leaseStartTo = leaseStartTo; }
}
