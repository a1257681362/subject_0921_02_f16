package com.evops.apartment.task.web;

import com.evops.apartment.task.dto.SignArchiveSubmitRequest;
import com.evops.apartment.task.engine.SignArchiveStepHandler;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.service.AsyncTaskService;
import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 签约归档后台任务接口：发起即返回任务号，前端按 taskNo 轮询进度（每步 5%），
 * 支持取消（停在下个一致步骤）与失败/取消后断点重跑。
 */
@RestController
@RequestMapping("/api/apartment/sign-archive/tasks")
public class SignArchiveTaskController {
    private final AsyncTaskService taskService;
    private final ObjectMapper objectMapper;

    public SignArchiveTaskController(AsyncTaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    /** 发起签约归档：登记 PENDING 立即返回，不等签章与归档。 */
    @PostMapping
    @RequirePermission("apartment:sign:submit")
    public ApiResponse<Map<String, Object>> submit(@Validated @RequestBody SignArchiveSubmitRequest req) {
        return ApiResponse.ok(view(taskService.submitSignArchive(req)));
    }

    /** 任务列表：status/contractNo 可选，按数据范围过滤（SELF 仅本人）。 */
    @GetMapping
    @RequirePermission("apartment:sign:view")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String contractNo) {
        return ApiResponse.ok(taskService.list(status, contractNo).stream()
                .map(this::view).collect(Collectors.toList()));
    }

    /** 轮询任务进度详情。 */
    @GetMapping("/{taskNo}")
    @RequirePermission("apartment:sign:view")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String taskNo) {
        return ApiResponse.ok(view(taskService.getByTaskNo(taskNo)));
    }

    /** 申请取消：RUNNING 在下个一致步骤边界停下，PENDING 立即取消。 */
    @PostMapping("/{taskNo}/cancel")
    @RequirePermission("apartment:sign:cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String taskNo) {
        return ApiResponse.ok(view(taskService.cancel(taskNo)));
    }

    /** 失败/取消任务断点重跑：从 currentStep+1 继续。 */
    @PostMapping("/{taskNo}/retry")
    @RequirePermission("apartment:sign:retry")
    public ApiResponse<Map<String, Object>> retry(@PathVariable String taskNo) {
        return ApiResponse.ok(view(taskService.retry(taskNo)));
    }

    /**
     * 组装前端轮询视图：任务字段 + 当前环节名 + 可取消/可重跑标记。
     * 进度恒为 5 的整数倍（20 个一致步骤）。
     */
    private Map<String, Object> view(AsyncTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskNo", t.getTaskNo());
        m.put("taskType", t.getTaskType());
        m.put("contractNo", t.getBizRef());
        m.put("propertyCode", t.getPropertyCode());
        m.put("traceId", t.getTraceId());
        m.put("status", t.getStatus());
        m.put("progress", t.getProgress());
        m.put("currentStep", t.getCurrentStep());
        m.put("totalSteps", t.getTotalSteps());
        m.put("stepName", SignArchiveStepHandler.stepNameOf(t.getCurrentStep()));
        m.put("failStep", t.getFailStep());
        m.put("failReason", t.getFailReason());
        m.put("cancelRequested", t.getCancelRequested());
        m.put("retryCount", t.getRetryCount());
        m.put("operator", t.getOperator());
        m.put("requestNo", t.getRequestNo());
        m.put("result", parseResult(t.getResultJson()));
        m.put("startedAt", t.getStartedAt());
        m.put("finishedAt", t.getFinishedAt());
        m.put("retainUntil", t.getRetainUntil());
        m.put("createdAt", t.getCreatedAt());
        boolean terminal = AsyncTask.STATUS_SUCCESS.equals(t.getStatus())
                || AsyncTask.STATUS_FAILED.equals(t.getStatus())
                || AsyncTask.STATUS_CANCELLED.equals(t.getStatus());
        m.put("cancelable", !terminal);
        m.put("retryable", AsyncTask.STATUS_FAILED.equals(t.getStatus())
                || AsyncTask.STATUS_CANCELLED.equals(t.getStatus()));
        return m;
    }

    private Object parseResult(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(resultJson, Object.class);
        } catch (Exception e) {
            return resultJson;
        }
    }
}
