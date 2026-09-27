package com.evops.apartment.pinyin;

import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * apt-11 拼音检索验收（总部巡检：验收时打印租约样例、纸面签字确认）：
 *
 * - GET  /api/apartment/leases/acceptance/console  自包含打印页（空壳不鉴权，数据接口强制鉴权）；
 * - POST /api/apartment/leases/acceptance/samples  幂等播种样例租约（多音字姓氏全覆盖）；
 * - GET  /api/apartment/leases/acceptance/data     样例 + 验收用例执行结果（全过方可打印签字）。
 *
 * 复用既有权限点（lease:view / lease:create），不新增权限点。
 */
@RestController
@RequestMapping("/api/apartment/leases/acceptance")
public class LeaseAcceptanceController {
    private final LeaseAcceptanceService acceptanceService;

    public LeaseAcceptanceController(LeaseAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /** 幂等播种：已存在的样例跳过，返回本次新建的合同号。 */
    @PostMapping("/samples")
    @RequirePermission("apartment:lease:create")
    public ApiResponse<List<String>> seedSamples() {
        return ApiResponse.ok(acceptanceService.seedSamples());
    }

    /** 验收数据：样例租约（含拼音冗余字段）+ 用例执行结果。 */
    @GetMapping("/data")
    @RequirePermission("apartment:lease:view")
    public ApiResponse<LeaseAcceptanceData> data() {
        return ApiResponse.ok(acceptanceService.buildAcceptanceData());
    }

    /** 自包含打印页空壳：浏览器直达不带令牌头，令牌在页内粘贴；真正鉴权在 /data 与 /samples。 */
    @GetMapping(value = "/console", produces = MediaType.TEXT_HTML_VALUE + ";charset=utf-8")
    public String console() {
        return LeaseAcceptancePage.render();
    }
}
