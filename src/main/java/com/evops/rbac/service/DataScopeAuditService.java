package com.evops.rbac.service;

import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.entity.DataScopeAudit;
import com.evops.rbac.mapper.DataScopeAuditMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 数据权限并集放行审计：按最宽角色放行的每一次数据访问留痕。
 * 审计写入失败默认随请求失败（fail-closed），不放无痕数据。
 */
@Service
public class DataScopeAuditService {
    /** query_params 列长 1024，超长截断保护。 */
    private static final int PARAMS_MAX_LEN = 1000;

    private final DataScopeAuditMapper auditMapper;

    public DataScopeAuditService(DataScopeAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    public void record(CurrentUser user, String resource, String requestNo,
                       String queryParams, Integer resultCount) {
        DataScopeAudit audit = new DataScopeAudit();
        audit.setRequestNo(requestNo);
        audit.setUsername(user.getUsername());
        audit.setUserRoles(String.join(",", user.roleCodes()));
        audit.setWidestScope(user.getScope().getLevel().name());
        audit.setGrantedByRole(user.getScope().isSelf() ? null : user.getScope().getGrantedByText());
        audit.setScopeValue(user.getScope().isScope() ? user.getScope().getScopeValue() : null);
        audit.setResource(resource);
        audit.setQueryParams(truncate(queryParams));
        audit.setResultCount(resultCount);
        audit.setRequestTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= PARAMS_MAX_LEN ? text : text.substring(0, PARAMS_MAX_LEN);
    }
}
