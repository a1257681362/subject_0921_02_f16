package com.evops.apartment.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 断点上下文：步骤间传递的已持久化产物。任务失败/取消/宕机后据此续跑，
 * 已完成步骤不重复执行（天然幂等），平台侧写操作另有业务幂等号兜底。
 */
public class SignArchiveCheckpoint {
    private Long leaseId;
    private String bizNo;
    /** 步骤 2 从台账核验得到的小区名（组装文书用）。 */
    private String community;
    private String flowNo;
    private String docToken;
    private String signUrl;
    private Long archiveId;
    private String fileName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime signedAt;

    public Long getLeaseId() { return leaseId; }
    public void setLeaseId(Long leaseId) { this.leaseId = leaseId; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getFlowNo() { return flowNo; }
    public void setFlowNo(String flowNo) { this.flowNo = flowNo; }
    public String getDocToken() { return docToken; }
    public void setDocToken(String docToken) { this.docToken = docToken; }
    public String getSignUrl() { return signUrl; }
    public void setSignUrl(String signUrl) { this.signUrl = signUrl; }
    public Long getArchiveId() { return archiveId; }
    public void setArchiveId(Long archiveId) { this.archiveId = archiveId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public LocalDateTime getSignedAt() { return signedAt; }
    public void setSignedAt(LocalDateTime signedAt) { this.signedAt = signedAt; }
}
