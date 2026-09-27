package com.evops.common;

import com.evops.common.trace.TraceContext;

/**
 * 统一响应体。apt-6 起固定携带 traceId（与响应头 X-Trace-Id 同值）：
 * 前端报错截图/录单里直接带追踪号，无需翻浏览器响应头即可按号聚合一次请求的全部日志。
 */
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private String traceId;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.message = "OK";
        response.traceId = TraceContext.getTraceId();
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> fail(String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.message = message;
        response.traceId = TraceContext.getTraceId();
        return response;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getTraceId() { return traceId; }
    public T getData() { return data; }
}
