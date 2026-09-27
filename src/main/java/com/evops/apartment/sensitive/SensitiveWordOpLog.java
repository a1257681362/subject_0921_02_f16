package com.evops.apartment.sensitive;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 敏感词库后台维护操作留痕：只增不改。
 */
@TableName("t_sensitive_word_op_log")
public class SensitiveWordOpLog {
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_DISABLE = "DISABLE";
    public static final String ACTION_ENABLE = "ENABLE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long wordId;
    private String wordText;
    private String action;
    private String beforeJson;
    private String afterJson;
    private String operator;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime opTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWordId() { return wordId; }
    public void setWordId(Long wordId) { this.wordId = wordId; }
    public String getWordText() { return wordText; }
    public void setWordText(String wordText) { this.wordText = wordText; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }
    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getOpTime() { return opTime; }
    public void setOpTime(LocalDateTime opTime) { this.opTime = opTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
