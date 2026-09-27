package com.evops.apartment.opslog;

import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 运维日志操作入口（公寓楼菜单，权限点仅授管家/片区管家）：
 *
 * - GET /api/apartment/ops-log/console 自包含检索台 HTML（空壳页，本身不鉴权：浏览器直达不带令牌头；
 *   数据接口 /entries 强制鉴权，未登录 401、非管家 403）；
 * - GET /api/apartment/ops-log/entries        按合同号/TraceId 聚合检索（必带其一，窗口 14 天）。
 *
 * 无权限角色（主管/管理员）菜单不可见，直接调数据接口 403。
 */
@RestController
@RequestMapping("/api/apartment/ops-log")
public class OpsLogController {
    private static final DateTimeFormatter PARAM_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpsLogQueryService queryService;

    public OpsLogController(OpsLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/entries")
    @RequirePermission("apartment:opslog:view")
    public ApiResponse<Map<String, Object>> entries(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String contractNo,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queryService.query(traceId, contractNo, level,
                parse(from), parse(to), limit));
    }

    /** 自包含检索台空壳：一线排障直接打开，令牌在页内粘贴；真正鉴权在 /entries。 */
    @GetMapping(value = "/console", produces = MediaType.TEXT_HTML_VALUE + ";charset=utf-8")
    public String console() {
        return OpsLogConsolePage.render();
    }

    private LocalDateTime parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String v = text.trim();
        try {
            return v.length() <= 10
                    ? LocalDateTime.parse(v + " 00:00:00", PARAM_TS)
                    : LocalDateTime.parse(v.replace('T', ' ').substring(0,
                    Math.min(v.length(), 19)), PARAM_TS);
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
    }
}
