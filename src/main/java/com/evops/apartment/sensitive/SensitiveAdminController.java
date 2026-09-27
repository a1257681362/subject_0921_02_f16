package com.evops.apartment.sensitive;

import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 敏感词后台：词典维护、维护留痕、命中痕迹查询。
 * 词典写操作需 apartment:sensitive:manage；痕迹查看需 apartment:sensitive:audit。
 */
@RestController
@RequestMapping("/api/apartment/sensitive")
public class SensitiveAdminController {
    private final SensitiveWordService wordService;
    private final SensitiveAuditService auditService;

    public SensitiveAdminController(SensitiveWordService wordService,
                                    SensitiveAuditService auditService) {
        this.wordService = wordService;
        this.auditService = auditService;
    }

    // ---------------- 词典维护（manage） ----------------

    @GetMapping("/words")
    @RequirePermission("apartment:sensitive:manage")
    public ApiResponse<List<SensitiveWord>> listWords(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(wordService.list(status, category, keyword));
    }

    @PostMapping("/words")
    @RequirePermission("apartment:sensitive:manage")
    public ApiResponse<SensitiveWord> createWord(@Validated @RequestBody SensitiveWordUpsertRequest req) {
        return ApiResponse.ok(wordService.create(req));
    }

    @PostMapping("/words/{id}")
    @RequirePermission("apartment:sensitive:manage")
    public ApiResponse<SensitiveWord> updateWord(@PathVariable Long id,
                                                 @Validated @RequestBody SensitiveWordUpsertRequest req) {
        return ApiResponse.ok(wordService.update(id, req));
    }

    @PostMapping("/words/{id}/status")
    @RequirePermission("apartment:sensitive:manage")
    public ApiResponse<SensitiveWord> setWordStatus(@PathVariable Long id,
                                                    @RequestBody Map<String, Boolean> body) {
        return ApiResponse.ok(wordService.setStatus(id, Boolean.TRUE.equals(body.get("active"))));
    }

    // ---------------- 操作痕迹（audit） ----------------

    /** 词典维护操作留痕（谁在何时新增/改/启停了哪个词）。 */
    @GetMapping("/word-logs")
    @RequirePermission("apartment:sensitive:audit")
    public ApiResponse<List<SensitiveWordOpLog>> wordLogs(
            @RequestParam(required = false) Long wordId) {
        return ApiResponse.ok(wordService.listOpLogs(wordId));
    }

    /** 命中痕迹（拦截/打码），可按账号、模块、业务定位、动作过滤，定位到人到字段到字位。 */
    @GetMapping("/audits")
    @RequirePermission("apartment:sensitive:audit")
    public ApiResponse<List<SensitiveAudit>> audits(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String bizRef,
            @RequestParam(required = false) String actionTaken,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        return ApiResponse.ok(auditService.search(username, module, bizRef, actionTaken, limit));
    }
}
