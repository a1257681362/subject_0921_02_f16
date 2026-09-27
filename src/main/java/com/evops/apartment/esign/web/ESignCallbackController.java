package com.evops.apartment.esign.web;

import com.evops.apartment.esign.ESignException;
import com.evops.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 电子签章平台回调入口（机器对机器，<b>不走登录令牌</b>）：
 *
 * - POST {@code /api/esign/callback/sign} 签署完成通知；
 * - 安全规矩全部前置在 {@link ESignCallbackFilter}：缺签名/错签名、超 8 分钟、
 *   同 nonce 15 分钟内重放，在进本控制器前即 401 拒绝，故这里<b>不挂</b>
 *   {@code @RequirePermission}（平台没有人工账号令牌）。
 *
 * 业务层只处理“验真通过”的回调：翻转签署态、即时唤醒等待任务；回调丢失时由任务轮询兜底。
 * 业务口径不通过（业务号格式错/无进行中任务/流水不匹配）回 400，便于平台按非 2xx 识别、不盲目重试。
 */
@RestController
public class ESignCallbackController {

    private final ESignCallbackService callbackService;

    public ESignCallbackController(ESignCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/api/esign/callback/sign")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signCompleted(
            @RequestBody ESignSignCallbackRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(callbackService.handleSignCompleted(request)));
        } catch (IllegalArgumentException | ESignException e) {
            // 业务拒绝（验签已通过）：bad request，不触发平台 5xx 重试，但明确非成功
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
        }
    }
}
