package com.evops.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 数据范围授权：挂在角色（ROLE）或账号（USER，用户级直接授权）上。
 * scope_level: SELF 仅本人经办 / SCOPE 指定片区（scope_value=小区名）/ ALL 全部。
 */
@TableName("t_rbac_data_scope")
public class RbacDataScope {
    public static final String SUBJECT_ROLE = "ROLE";
    public static final String SUBJECT_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String subjectType;
    private Long subjectId;
    private String scopeLevel;
    private String scopeValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }
    public String getScopeLevel() { return scopeLevel; }
    public void setScopeLevel(String scopeLevel) { this.scopeLevel = scopeLevel; }
    public String getScopeValue() { return scopeValue; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
