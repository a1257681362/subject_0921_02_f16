package com.evops.apartment.controller;

import com.evops.apartment.dto.CheckoutRequest;
import com.evops.apartment.dto.LeaseCreateRequest;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.entity.LeaseCheckout;
import com.evops.apartment.service.CheckoutService;
import com.evops.apartment.service.LeaseExportService;
import com.evops.apartment.service.LeaseService;
import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 租约：建档、组合检索、退租、导出。
 */
@RestController
@RequestMapping("/api/apartment/leases")
public class LeaseController {
    private final LeaseService leaseService;
    private final CheckoutService checkoutService;
    private final LeaseExportService exportService;

    public LeaseController(LeaseService leaseService,
                           CheckoutService checkoutService,
                           LeaseExportService exportService) {
        this.leaseService = leaseService;
        this.checkoutService = checkoutService;
        this.exportService = exportService;
    }

    @PostMapping
    @RequirePermission("apartment:lease:create")
    public ApiResponse<Lease> create(@Validated @RequestBody LeaseCreateRequest req) {
        return ApiResponse.ok(leaseService.create(req));
    }

    @GetMapping
    @RequirePermission("apartment:lease:view")
    public ApiResponse<List<Lease>> search(
            @RequestParam(required = false) String contractNo,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startTo,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(leaseService.search(contractNo, startFrom, startTo, keyword));
    }

    /** 租约导出 CSV：参数与检索接口一致（含拼音 keyword），姓名/手机/证件号按 @Sensitive 同一套规则脱敏。 */
    @GetMapping("/export")
    @RequirePermission("apartment:lease:export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String contractNo,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startTo,
            @RequestParam(required = false) String keyword) {
        String csv = exportService.exportLeasesCsv(contractNo, startFrom, startTo, keyword);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=leases.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/checkout")
    @RequirePermission("apartment:checkout:handle")
    public ApiResponse<LeaseCheckout> checkout(@Validated @RequestBody CheckoutRequest req) {
        return ApiResponse.ok(checkoutService.checkout(req));
    }

    @GetMapping("/{contractNo}/checkout")
    @RequirePermission("apartment:checkout:view")
    public ApiResponse<Map<String, Object>> checkoutDetail(@PathVariable String contractNo) {
        return ApiResponse.ok(checkoutService.detail(contractNo));
    }
}
