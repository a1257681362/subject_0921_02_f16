package com.evops.apartment.controller;

import com.evops.apartment.dto.PropertyEntryCreateRequest;
import com.evops.apartment.dto.PropertyReverseRequest;
import com.evops.apartment.entity.PropertyLedger;
import com.evops.apartment.service.PropertyLedgerService;
import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 房源台账：登记、冲销、流水查询。
 */
@RestController
@RequestMapping("/api/apartment/property")
public class PropertyLedgerController {
    private final PropertyLedgerService ledgerService;

    public PropertyLedgerController(PropertyLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/entries")
    @RequirePermission("apartment:property:register")
    public ApiResponse<PropertyLedger> register(@Validated @RequestBody PropertyEntryCreateRequest req) {
        return ApiResponse.ok(ledgerService.register(req));
    }

    @PostMapping("/reversals")
    @RequirePermission("apartment:property:reverse")
    public ApiResponse<PropertyLedger> reverse(@Validated @RequestBody PropertyReverseRequest req) {
        return ApiResponse.ok(ledgerService.reverse(req));
    }

    @GetMapping("/entries")
    @RequirePermission("apartment:property:view")
    public ApiResponse<List<PropertyLedger>> list(@RequestParam(required = false) String propertyCode) {
        return ApiResponse.ok(ledgerService.list(propertyCode));
    }
}
