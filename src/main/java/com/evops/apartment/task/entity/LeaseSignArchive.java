package com.evops.apartment.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 签约归档结果：电子签章流程走完后的归档凭证。
 * 合同号唯一——同一合同重复发起/断点重跑命中既有记录，不产生第二份归档。
 */
@TableName("t_lease_sign_archive")
public class LeaseSignArchive {
    /** 平台侧流转中：文书已生成，等待签署方签署。 */
    public static final String SIGN_SIGNING = "SIGNING";
    /** 租客已签署。 */
    public static final String SIGN_SIGNED = "SIGNED";
    /** 平台盖章完成。 */
    public static final String SIGN_SEALED = "SEALED";
    /** 已归档：签署文件回传并入库。 */
    public static final String SIGN_FINISHED = "FINISHED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractNo;
    private Long leaseId;
    private String platformFlowNo;
    private String platformDocToken;
    private String signStatus;
    private String fileName;
    private String fileContent;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime signedAt;
    private String requestNo;
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;
    private Integer dataVersion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public Long getLeaseId() { return leaseId; }
    public void setLeaseId(Long leaseId) { this.leaseId = leaseId; }
    public String getPlatformFlowNo() { return platformFlowNo; }
    public void setPlatformFlowNo(String platformFlowNo) { this.platformFlowNo = platformFlowNo; }
    public String getPlatformDocToken() { return platformDocToken; }
    public void setPlatformDocToken(String platformDocToken) { this.platformDocToken = platformDocToken; }
    public String getSignStatus() { return signStatus; }
    public void setSignStatus(String signStatus) { this.signStatus = signStatus; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileContent() { return fileContent; }
    public void setFileContent(String fileContent) { this.fileContent = fileContent; }
    public LocalDateTime getSignedAt() { return signedAt; }
    public void setSignedAt(LocalDateTime signedAt) { this.signedAt = signedAt; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
    public Integer getDataVersion() { return dataVersion; }
    public void setDataVersion(Integer dataVersion) { this.dataVersion = dataVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
