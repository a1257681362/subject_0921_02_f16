package com.evops.apartment.controller;

import com.evops.apartment.dto.HandoverConfirmRequest;
import com.evops.apartment.dto.HandoverCreateRequest;
import com.evops.apartment.entity.HandoverRecord;
import com.evops.apartment.service.HandoverService;
import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 交接班：登记（交班）、确认（接班）、检索、详情。纸质交接本线上化。
 */
@RestController
@RequestMapping("/api/apartment/handovers")
public class HandoverController {
    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    @PostMapping
    @RequirePermission("apartment:handover:create")
    public ApiResponse<HandoverRecord> create(@Validated @RequestBody HandoverCreateRequest req) {
        return ApiResponse.ok(handoverService.create(req));
    }

    @PostMapping("/{id}/confirm")
    @RequirePermission("apartment:handover:confirm")
    public ApiResponse<HandoverRecord> confirm(@PathVariable Long id,
                                               @Validated @RequestBody HandoverConfirmRequest req) {
        return ApiResponse.ok(handoverService.confirm(id, req));
    }

    @GetMapping
    @RequirePermission("apartment:handover:view")
    public ApiResponse<List<HandoverRecord>> search(
            @RequestParam(required = false) String community,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) String shift,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate dateTo,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(handoverService.search(community, building, shift, dateFrom, dateTo, status));
    }

    @GetMapping("/{id}")
    @RequirePermission("apartment:handover:view")
    public ApiResponse<HandoverRecord> detail(@PathVariable Long id) {
        return ApiResponse.ok(handoverService.detail(id));
    }
}
