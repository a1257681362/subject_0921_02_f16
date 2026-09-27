package com.evops.apartment.quota.web;

import com.evops.apartment.quota.QuotaService;
import com.evops.apartment.quota.dto.QuotaAdjustRequest;
import com.evops.apartment.quota.dto.QuotaApplyRequest;
import com.evops.apartment.quota.dto.QuotaCancelRequest;
import com.evops.apartment.quota.dto.QuotaConfirmRequest;
import com.evops.apartment.quota.entity.QuotaBalance;
import com.evops.apartment.quota.entity.QuotaHold;
import com.evops.apartment.quota.entity.QuotaJournal;
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

/**
 * 出租率额度预占：申请先占余量、确认实扣、取消释放（20 分钟超时由后台自动释放），
 * 余量/预占单/额度流水全程可查；配额调整仅主管/管理员且留痕。
 */
@RestController
@RequestMapping("/api/apartment/quota")
public class QuotaController {
    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 申请预占：占用 1 个余量，返回预占单（含单号与到期时间）。 */
    @PostMapping("/holds")
    @RequirePermission("apartment:quota:apply")
    public ApiResponse<QuotaHold> apply(@Validated @RequestBody QuotaApplyRequest req) {
        return ApiResponse.ok(quotaService.apply(req));
    }

    /** 确认实扣：预占额转已实扣；过期/已释放/已确认均业务失败。 */
    @PostMapping("/holds/{holdNo}/confirm")
    @RequirePermission("apartment:quota:confirm")
    public ApiResponse<QuotaHold> confirm(@PathVariable String holdNo,
                                          @Validated @RequestBody QuotaConfirmRequest req) {
        req.setHoldNo(holdNo);
        return ApiResponse.ok(quotaService.confirm(req));
    }

    /** 主动取消：释放预占额（与超时自动释放互斥，只生效一次）。 */
    @PostMapping("/holds/{holdNo}/cancel")
    @RequirePermission("apartment:quota:cancel")
    public ApiResponse<QuotaHold> cancel(@PathVariable String holdNo,
                                         @Validated @RequestBody QuotaCancelRequest req) {
        req.setHoldNo(holdNo);
        return ApiResponse.ok(quotaService.cancel(req));
    }

    /** 预占单检索：community/status/bizRef 均可选，叠加数据范围（SELF 仅本人申请）。 */
    @GetMapping("/holds")
    @RequirePermission("apartment:quota:view")
    public ApiResponse<List<QuotaHold>> holds(@RequestParam(required = false) String community,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String bizRef) {
        return ApiResponse.ok(quotaService.searchHolds(community, status, bizRef));
    }

    /** 余量查询：配额/已实扣/预占中/余量。 */
    @GetMapping("/balances")
    @RequirePermission("apartment:quota:view")
    public ApiResponse<QuotaBalance> balance(@RequestParam String community) {
        return ApiResponse.ok(quotaService.getBalance(community));
    }

    /** 额度流水：余量变化全程可查、可对账（必须指定小区）。 */
    @GetMapping("/journals")
    @RequirePermission("apartment:quota:view")
    public ApiResponse<List<QuotaJournal>> journals(@RequestParam String community,
                                                    @RequestParam(required = false) String changeType,
                                                    @RequestParam(required = false) String bizRef) {
        return ApiResponse.ok(quotaService.searchJournal(community, changeType, bizRef));
    }

    /** 配额调整（含首次开户）：下调不得击穿在途单；调整落流水留痕。仅主管/管理员。 */
    @PostMapping("/adjustments")
    @RequirePermission("apartment:quota:adjust")
    public ApiResponse<QuotaBalance> adjust(@Validated @RequestBody QuotaAdjustRequest req) {
        return ApiResponse.ok(quotaService.adjust(req));
    }
}
