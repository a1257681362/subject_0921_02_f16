package com.evops.apartment.sensitive;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 敏感词命中痕迹：每次命中（拦截/打码）逐词逐字段留痕，出状况可定位到账号、字段、字位。
 * BLOCK 场景由独立事务先落库，业务事务随后回滚不影响本行。
 */
@TableName("t_sensitive_audit")
public class SensitiveAudit {
    public static final String ACTION_BLOCK = "BLOCK";
    public static final String ACTION_MASK = "MASK";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private String username;
    private String module;
    private String bizRef;
    private String fieldPath;
    private String wordText;
    private String category;
    private String actionTaken;
    private String rawExcerpt;
    private Integer startIndex;
    private Integer endIndex;
    private String maskedText;
    private Long sourceId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public String getBizRef() { return bizRef; }
    public void setBizRef(String bizRef) { this.bizRef = bizRef; }
    public String getFieldPath() { return fieldPath; }
    public void setFieldPath(String fieldPath) { this.fieldPath = fieldPath; }
    public String getWordText() { return wordText; }
    public void setWordText(String wordText) { this.wordText = wordText; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getActionTaken() { return actionTaken; }
    public void setActionTaken(String actionTaken) { this.actionTaken = actionTaken; }
    public String getRawExcerpt() { return rawExcerpt; }
    public void setRawExcerpt(String rawExcerpt) { this.rawExcerpt = rawExcerpt; }
    public Integer getStartIndex() { return startIndex; }
    public void setStartIndex(Integer startIndex) { this.startIndex = startIndex; }
    public Integer getEndIndex() { return endIndex; }
    public void setEndIndex(Integer endIndex) { this.endIndex = endIndex; }
    public String getMaskedText() { return maskedText; }
    public void setMaskedText(String maskedText) { this.maskedText = maskedText; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public LocalDateTime getRequestTime() { return requestTime; }
    public void setRequestTime(LocalDateTime requestTime) { this.requestTime = requestTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
