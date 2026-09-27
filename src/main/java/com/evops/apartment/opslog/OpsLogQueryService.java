package com.evops.apartment.opslog;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.common.opslog.OpsLogEntry;
import com.evops.common.opslog.OpsLogEntryMapper;
import com.evops.common.opslog.OpsLogProperties;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.RbacForbiddenException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运维日志检索（公寓楼菜单，仅管家角色有按钮权限）：
 *
 * - 必带合同号或 TraceId 其一，时间窗强制 14 天，单次条数封顶，任何组合都不允许全表拖；
 * - 按合同号：沿用 apt-3 数据范围口径——SELF 仅本人经办合同，SCOPE 仅授权片区合同；
 *   合同尚未建档（建档请求即失败）时只放行 SELF 且仅返回本人日志行；
 * - 仅按 TraceId：SELF/SCOPE 要求该 TraceId 的日志中确有本人发起的行，
 *   才返回该 Trace 全量行（含框架告警等无账号行，保证排障完整）；
 * - 每次检索落数据放行审计（谁、按什么条件、命中多少行）。
 */
@Service
public class OpsLogQueryService {
    private final OpsLogEntryMapper logMapper;
    private final LeaseMapper leaseMapper;
    private final ApartmentScopeGuard scopeGuard;
    private final OpsLogProperties properties;

    public OpsLogQueryService(OpsLogEntryMapper logMapper,
                              LeaseMapper leaseMapper,
                              ApartmentScopeGuard scopeGuard,
                              OpsLogProperties properties) {
        this.logMapper = logMapper;
        this.leaseMapper = leaseMapper;
        this.scopeGuard = scopeGuard;
        this.properties = properties;
    }

    public Map<String, Object> query(String traceId, String contractNo, String level,
                                     LocalDateTime from, LocalDateTime to, Integer limit) {
        CurrentUser user = CurrentUserHolder.get();
        String t = trim(traceId);
        String c = trim(contractNo);
        if (t == null && c == null) {
            throw new IllegalArgumentException("检索必须提供合同号或 TraceId（不支持无条件查询）");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(properties.getQueryWindowDays());
        LocalDateTime effectiveFrom = from == null || from.isBefore(windowStart) ? windowStart : from;
        LocalDateTime effectiveTo = to == null || to.isAfter(now) ? now : to;
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("查询时间范围无效");
        }
        int effectiveLimit = limit == null || limit <= 0
                ? properties.getQueryLimit() : Math.min(limit, properties.getQueryLimit());

        // 数据范围校验（仅合同号维度可映射房源/经办人；ALL 角色直接放行）
        boolean restrictToSelfRows = false;
        if (user == null || !user.getScope().isAll()) {
            if (c != null) {
                restrictToSelfRows = authorizeByContract(user, c);
            } else {
                requireTraceOwnedBy(user, t);
            }
        }

        QueryWrapper<OpsLogEntry> q = new QueryWrapper<>();
        String levelFilter = trim(level);
        if (levelFilter != null) {
            levelFilter = levelFilter.toUpperCase();
        }
        q.eq(t != null, "trace_id", t);
        q.eq(c != null, "contract_no", c);
        // 合同建档失败等无租约场景：SELF 仅能看本人账号的行
        q.eq(restrictToSelfRows, "username", user == null ? null : user.getUsername());
        q.eq(levelFilter != null, "level", levelFilter);
        q.ge("log_time", effectiveFrom);
        q.le("log_time", effectiveTo);
        q.orderByAsc("id");
        q.last("LIMIT " + effectiveLimit);
        List<OpsLogEntry> rows = logMapper.selectList(q);

        String params = "traceId=" + (t == null ? "" : t)
                + ",contractNo=" + (c == null ? "" : c)
                + ",level=" + (level == null ? "" : level)
                + ",from=" + effectiveFrom + ",to=" + effectiveTo;
        scopeGuard.audit("apartment:opslog:view", null, params, rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", effectiveFrom);
        result.put("to", effectiveTo);
        result.put("limit", effectiveLimit);
        result.put("count", rows.size());
        result.put("droppedByCollector", com.evops.common.opslog.OpsDbAppender.getDroppedCount());
        result.put("rows", rows.stream().map(this::view).collect(Collectors.toList()));
        return result;
    }

    /**
     * 按合同号鉴权。
     *
     * @return true 表示合同无租约（建档前失败等），调用方需把结果限制为本人日志行
     */
    private boolean authorizeByContract(CurrentUser user, String contractNo) {
        Lease lease = leaseMapper.selectOne(new QueryWrapper<Lease>().eq("contract_no", contractNo));
        if (lease == null) {
            // 合同可能在建档阶段就失败（如敏感词拦截/唯一冲突），没有房源可映射范围：
            // 仅 SELF 角色放行且只返回本人日志；SCOPE 无法确认片区，拒绝
            if (user.getScope().isSelf()) {
                return true;
            }
            throw new RbacForbiddenException("该合同不在授权片区范围内（或不存在）");
        }
        scopeGuard.requirePropertyCodeInScope(lease.getPropertyCode());
        scopeGuard.requireSelfOperated(lease.getOperator());
        return false;
    }

    /** 仅按 TraceId 检索：该 Trace 必须确有本人发起的日志行，防止枚举他人 TraceId。 */
    private void requireTraceOwnedBy(CurrentUser user, String traceId) {
        Long mine = logMapper.selectCount(new QueryWrapper<OpsLogEntry>()
                .eq("trace_id", traceId)
                .eq("username", user.getUsername()));
        if (mine == null || mine == 0) {
            throw new RbacForbiddenException("TraceId 不属于本人发起的请求，无权检索");
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private Map<String, Object> view(OpsLogEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", e.getLogTime());
        m.put("level", e.getLevel());
        m.put("traceId", e.getTraceId());
        m.put("contractNo", e.getContractNo());
        m.put("username", e.getUsername());
        m.put("thread", e.getThreadName());
        m.put("logger", e.getLoggerName());
        m.put("message", e.getMessage());
        m.put("stack", e.getStack());
        return m;
    }
}
