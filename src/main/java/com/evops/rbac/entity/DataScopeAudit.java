package com.evops.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 数据权限并集放行审计：按最宽角色放行的每一次数据访问逐行留痕。
 */
@TableName("t_data_scope_audit")
public class DataScopeAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private String username;
    private String userRoles;
    private String widestScope;
    private String grantedByRole;
    private String scopeValue;
    private String resource;
    private String queryParams;
    private Integer resultCount;
    private LocalDateTime requestTime;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserRoles() { return userRoles; }
    public void setUserRoles(String userRoles) { this.userRoles = userRoles; }
    public String getWidestScope() { return widestScope; }
    public void setWidestScope(String widestScope) { this.widestScope = widestScope; }
    public String getGrantedByRole() { return grantedByRole; }
    public void setGrantedByRole(String grantedByRole) { this.grantedByRole = grantedByRole; }
    public String getScopeValue() { return scopeValue; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getQueryParams() { return queryParams; }
    public void setQueryParams(String queryParams) { this.queryParams = queryParams; }
    public Integer getResultCount() { return resultCount; }
    public void setResultCount(Integer resultCount) { this.resultCount = resultCount; }
    public LocalDateTime getRequestTime() { return requestTime; }
    public void setRequestTime(LocalDateTime requestTime) { this.requestTime = requestTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
