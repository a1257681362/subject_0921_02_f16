package com.evops.common;

import com.evops.apartment.sensitive.SensitiveBlockException;
import com.evops.rbac.core.RbacForbiddenException;
import com.evops.rbac.core.RbacUnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    /** 敏感词拦截：业务校验口径（200 + success=false），消息内带字段与字位，便于管家定位修改。 */
    @ExceptionHandler(SensitiveBlockException.class)
    public ApiResponse<Void> handleSensitiveBlock(SensitiveBlockException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        FieldError error = ex.getBindingResult().getFieldError();
        return ApiResponse.fail(error == null ? "参数校验失败" : error.getDefaultMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        return ApiResponse.fail("唯一约束冲突：相同合同号或单号的记录已存在");
    }

    /** 未认证：未登录、令牌失效/过期、账号停用。 */
    @ExceptionHandler(RbacUnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(RbacUnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(ex.getMessage()));
    }

    /** 已认证但功能权限不足或数据范围越界。 */
    @ExceptionHandler(RbacForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(RbacForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        // 堆栈随当前 TraceId 采集入库，管家凭响应体 traceId 即可在运维日志菜单拉出完整堆栈
        log.error("未预期异常，返回兜底错误", ex);
        return ApiResponse.fail("系统处理失败");
    }
}
