package com.evops.apartment.report.web;

import com.evops.apartment.report.dto.SignReportGenerateRequest;
import com.evops.apartment.report.entity.SignReportVersion;
import com.evops.apartment.report.service.SignReportService;
import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

/**
 * 签约结果正式 Word 报告（apt-8）：生成（落归档目录 + 版本登记）、版本列表/详情、文件下载。
 * 权限：生成 {@code apartment:sign-report:generate}，查看与下载 {@code apartment:sign-report:view}。
 */
@RestController
@RequestMapping("/api/apartment/sign-reports")
public class SignReportController {
    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final SignReportService reportService;

    public SignReportController(SignReportService reportService) {
        this.reportService = reportService;
    }

    /** 生成一版报告：渲染 Word、落归档目录、版本号 +1，返回版本登记。 */
    @PostMapping("/generate")
    @RequirePermission("apartment:sign-report:generate")
    public ApiResponse<SignReportVersion> generate(@Validated @RequestBody SignReportGenerateRequest req) {
        return ApiResponse.ok(reportService.generate(req));
    }

    /** 版本记录：buildingName 可选，按数据范围过滤。 */
    @GetMapping
    @RequirePermission("apartment:sign-report:view")
    public ApiResponse<List<SignReportVersion>> list(@RequestParam(required = false) String buildingName) {
        return ApiResponse.ok(reportService.list(buildingName));
    }

    /** 版本详情。 */
    @GetMapping("/{id}")
    @RequirePermission("apartment:sign-report:view")
    public ApiResponse<SignReportVersion> detail(@PathVariable Long id) {
        return ApiResponse.ok(reportService.getById(id));
    }

    /** 下载该版归档 docx（历史文件不可变，重复下载得到同一字节）。 */
    @GetMapping("/{id}/download")
    @RequirePermission("apartment:sign-report:view")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        SignReportVersion version = reportService.getById(id); // 先做范围放行
        byte[] body = reportService.readArchiveFile(version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(version.getFileName()))
                .contentType(DOCX)
                .contentLength(body.length)
                .body(body);
    }

    /** 中文文件名：ASCII 回退名 + RFC 5987 filename*。 */
    private static String contentDisposition(String fileName) {
        try {
            String encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
            return "attachment; filename=\"report.docx\"; filename*=UTF-8''" + encoded;
        } catch (UnsupportedEncodingException e) {
            return "attachment; filename=\"report.docx\"";
        }
    }
}
