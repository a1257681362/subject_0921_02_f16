package com.evops.apartment.sensitive;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 敏感词词典条目（后台维护）。wordText 为录入原文，引擎加载时归一化。
 */
@TableName("t_sensitive_word")
public class SensitiveWord {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String CATEGORY_AD = "AD";
    public static final String CATEGORY_ILLEGAL = "ILLEGAL";
    public static final String CATEGORY_OTHER = "OTHER";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String wordText;
    private String category;
    /** 1=MASK 打码放行；2=BLOCK 拦截。 */
    private Integer actionLevel;
    private String normalizeHint;
    private String status;
    private String createdBy;
    private String updatedBy;
    private String remark;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWordText() { return wordText; }
    public void setWordText(String wordText) { this.wordText = wordText; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getActionLevel() { return actionLevel; }
    public void setActionLevel(Integer actionLevel) { this.actionLevel = actionLevel; }
    public String getNormalizeHint() { return normalizeHint; }
    public void setNormalizeHint(String normalizeHint) { this.normalizeHint = normalizeHint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
