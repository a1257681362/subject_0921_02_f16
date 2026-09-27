package com.evops.apartment.task.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.entity.PropertyLedger;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.mapper.PropertyLedgerMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.apartment.sensitive.SensitiveContext;
import com.evops.apartment.sensitive.SensitiveGuard;
import com.evops.apartment.task.TaskProperties;
import com.evops.apartment.task.dto.SignArchivePayload;
import com.evops.apartment.task.dto.SignArchiveSubmitRequest;
import com.evops.apartment.task.engine.SignArchiveStepHandler;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import com.evops.common.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.RbacForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 后台任务登记与查询：慢操作接口只做“校验 + 登记”立即返回，执行由调度器异步认领。
 *
 * - 提交在请求线程内完成数据范围校验与敏感词扫描（拦截即时反馈，打码后入 payload）；
 * - 任务列表/详情按当前身份最宽范围过滤（SELF 本人 / SCOPE 授权片区 / ALL 全部）；
 * - 取消、重跑同样做归属与范围校验。
 */
@Service
public class AsyncTaskService {
    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final AsyncTaskMapper taskMapper;
    private final PropertyLedgerMapper ledgerMapper;
    private final LeaseMapper leaseMapper;
    private final ApartmentScopeGuard scopeGuard;
    private final SensitiveGuard sensitiveGuard;
    private final SignArchiveStepHandler signHandler;
    private final ObjectMapper objectMapper;
    private final TaskProperties properties;

    public AsyncTaskService(AsyncTaskMapper taskMapper,
                            PropertyLedgerMapper ledgerMapper,
                            LeaseMapper leaseMapper,
                            ApartmentScopeGuard scopeGuard,
                            SensitiveGuard sensitiveGuard,
                            SignArchiveStepHandler signHandler,
                            ObjectMapper objectMapper,
                            TaskProperties properties) {
        this.taskMapper = taskMapper;
        this.ledgerMapper = ledgerMapper;
        this.leaseMapper = leaseMapper;
        this.scopeGuard = scopeGuard;
        this.sensitiveGuard = sensitiveGuard;
        this.signHandler = signHandler;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 发起签约归档后台任务：校验通过后登记 PENDING 立即返回（不阻塞等待签署/归档）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AsyncTask submitSignArchive(SignArchiveSubmitRequest req) {
        // 合同号入日志上下文：范围/房源/重复发起等拒绝分支的日志也能按合同号聚合。
        // 作用域关闭自动还原，规矩在 TraceContext 里，不依赖本方法手写 finally。
        try (TraceContext.Scope ignored = TraceContext.contractScope(req.getContractNo())) {
            return doSubmitSignArchive(req);
        }
    }

    private AsyncTask doSubmitSignArchive(SignArchiveSubmitRequest req) {
        scopeGuard.requirePropertyCodeInScope(req.getPropertyCode());
        String operator = scopeGuard.resolveOperator(req.getOperator());

        // 房源存在性提前到提交时校验，管家拿到即时反馈（步骤 2 执行时仍会再核一次）
        Long ledgerCount = ledgerMapper.selectCount(new QueryWrapper<PropertyLedger>()
                .eq("property_code", req.getPropertyCode())
                .eq("entry_type", PropertyLedger.TYPE_REGISTER));
        if (ledgerCount == null || ledgerCount == 0) {
            throw new IllegalArgumentException("房源台账中不存在该房源，无法发起签约: " + req.getPropertyCode());
        }

        // 敏感词在请求线程同步处置：命中拦截级直接业务失败，打码级以处置后文本入任务
        SensitiveContext ctx = SensitiveContext.builder(SensitiveContext.MODULE_LEASE, operator)
                .bizRef(req.getContractNo())
                .requestNo(req.getRequestNo())
                .build();
        String safeRemark = sensitiveGuard.checkField(ctx, "remark", req.getRemark());

        // 同一合同存在进行中的任务时拒绝，避免重复签约/重复平台流程（断点重跑请用 retry）
        Long active = taskMapper.selectCount(new QueryWrapper<AsyncTask>()
                .eq("task_type", AsyncTask.TYPE_SIGN_ARCHIVE)
                .eq("biz_ref", req.getContractNo())
                .in("status", Arrays.asList(AsyncTask.STATUS_PENDING, AsyncTask.STATUS_RUNNING)));
        if (active != null && active > 0) {
            throw new IllegalArgumentException("该合同已有进行中的签约归档任务，请勿重复发起");
        }
        // 已建档/已签约的合同不允许再次发起（归档 uk 之外的前置拦截，管家拿到即时反馈）
        Long leased = leaseMapper.selectCount(new QueryWrapper<Lease>().eq("contract_no", req.getContractNo()));
        if (leased != null && leased > 0) {
            throw new IllegalArgumentException("该合同已建档/签约，不能重复发起签约归档");
        }

        SignArchivePayload payload = new SignArchivePayload();
        payload.setContractNo(req.getContractNo());
        payload.setPropertyCode(req.getPropertyCode());
        payload.setTenantName(req.getTenantName());
        payload.setTenantPhone(req.getTenantPhone());
        payload.setTenantIdCard(req.getTenantIdCard());
        payload.setLeaseStart(req.getLeaseStart());
        payload.setLeaseEnd(req.getLeaseEnd());
        payload.setMonthlyRent(req.getMonthlyRent());
        payload.setDeposit(req.getDeposit());
        payload.setRemark(safeRemark);

        AsyncTask task = new AsyncTask();
        task.setTaskNo("TASK-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        task.setTaskType(AsyncTask.TYPE_SIGN_ARCHIVE);
        task.setBizRef(req.getContractNo());
        task.setPropertyCode(req.getPropertyCode());
        task.setStatus(AsyncTask.STATUS_PENDING);
        task.setProgress(0);
        task.setCurrentStep(0);
        task.setTotalSteps(signHandler.totalSteps());
        task.setCancelRequested(false);
        task.setRetryCount(0);
        task.setPayload(writeJson(payload));
        task.setRequestNo(req.getRequestNo());
        task.setOperator(operator);
        // 存发起请求的 TraceId：工作线程认领后续绑，后台 20 步日志与该请求同号
        task.setTraceId(TraceContext.getTraceId());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        scopeGuard.audit("apartment:sign:submit", req.getRequestNo(),
                "taskNo=" + task.getTaskNo() + ",contractNo=" + req.getContractNo(), null);
        log.info("签约归档任务已提交 taskNo={} contractNo={} propertyCode={} traceId={} requestNo={}",
                task.getTaskNo(), req.getContractNo(), req.getPropertyCode(),
                task.getTraceId(), req.getRequestNo());
        return task;
    }

    /** 轮询任务详情：按数据范围放行。 */
    public AsyncTask getByTaskNo(String taskNo) {
        AsyncTask task = taskMapper.selectOne(new QueryWrapper<AsyncTask>().eq("task_no", taskNo));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskNo);
        }
        requireVisible(task);
        scopeGuard.audit("apartment:sign:view", null, "taskNo=" + taskNo, null);
        return task;
    }

    /** 任务列表：状态/合同号可选，叠加最宽数据范围。 */
    public List<AsyncTask> list(String status, String contractNo) {
        QueryWrapper<AsyncTask> query = new QueryWrapper<>();
        query.eq("task_type", AsyncTask.TYPE_SIGN_ARCHIVE);
        query.eq(status != null && !status.isEmpty(), "status", status);
        query.eq(contractNo != null && !contractNo.isEmpty(), "biz_ref", contractNo);
        applyTaskScope(query);
        query.orderByDesc("id");
        List<AsyncTask> tasks = taskMapper.selectList(query);
        scopeGuard.audit("apartment:sign:view", null,
                "status=" + (status == null ? "" : status) + ",contractNo=" + (contractNo == null ? "" : contractNo),
                tasks.size());
        return tasks;
    }

    /**
     * 申请取消：
     * - PENDING（排队中，含等签署延后重领）：条件更新直接落 CANCELLED，停在第 0 步/当前断点；
     * - RUNNING：置取消标记，执行器在下一个一致步骤边界停下（当前步骤提交完）。
     *
     * 取消与调度的“认领（PENDING→RUNNING）”、执行器第 12 步“等待签署退回（RUNNING→PENDING）”
     * 可能并发翻转，读到的状态在更新时未必仍成立。因此不假定单次命中：按下一条条件更新追上
     * 当前状态，有界重读收敛——RUNNING 置标落空多因刚退回 PENDING，下一轮补 PENDING→CANCELLED；
     * PENDING 落空多因刚被领走，下一轮改置 RUNNING 标记。两条写路径与执行器退回的 CAS
     * （见 TaskStepStore#backToPending）互为兜底，任一时序下取消都不会被静默吞掉。
     */
    @Transactional(rollbackFor = Exception.class)
    public AsyncTask cancel(String taskNo) {
        AsyncTask owned = requireOwned(taskNo);
        if (isTerminal(owned.getStatus())) {
            throw new IllegalArgumentException("任务已结束（" + owned.getStatus() + "），无法取消");
        }

        boolean applied = false;
        AsyncTask cur = owned;
        // 状态最多在 PENDING/RUNNING 间翻转，给有限次收敛；超出按最新状态定论，不空转
        for (int attempt = 0; attempt < 4 && !applied; attempt++) {
            if (attempt > 0) {
                cur = taskMapper.selectById(owned.getId());
                if (cur == null) {
                    throw new IllegalArgumentException("任务不存在: " + taskNo);
                }
            }
            String status = cur.getStatus();
            if (isTerminal(status)) {
                throw new IllegalArgumentException("任务已结束（" + status + "），无法取消");
            }
            LocalDateTime now = LocalDateTime.now();
            if (AsyncTask.STATUS_PENDING.equals(status)) {
                // 仅仍为 PENDING 才落 CANCELLED；刚被领走则 0 行，下一轮按 RUNNING 置标
                if (cancelFromPending(owned.getId(), now) == 1) {
                    applied = true;
                }
            } else if (AsyncTask.STATUS_RUNNING.equals(status)) {
                // 仅仍为 RUNNING 才置取消标记；恰在 WAIT 退回后则 0 行，下一轮按 PENDING 补取消
                if (flagRunning(owned.getId(), now) == 1) {
                    applied = true;
                }
            } else {
                throw new IllegalArgumentException("任务状态异常，无法取消: " + status);
            }
        }

        AsyncTask latest = taskMapper.selectById(owned.getId());
        if (!applied) {
            // 有限次内未直接命中：若已被执行器在等待边界/步骤边界落 CANCELLED，按幂等成功返回；
            // 否则状态仍在快速翻转，让管家刷新重试而不是假装取消成功
            if (latest == null || !AsyncTask.STATUS_CANCELLED.equals(latest.getStatus())) {
                throw new IllegalArgumentException("任务状态刚变化，取消未生效，请刷新后重试");
            }
        }
        scopeGuard.audit("apartment:sign:cancel", latest.getRequestNo(),
                "taskNo=" + taskNo + ",atStep=" + latest.getCurrentStep(), null);
        return latest;
    }

    private boolean isTerminal(String status) {
        return AsyncTask.STATUS_SUCCESS.equals(status)
                || AsyncTask.STATUS_FAILED.equals(status)
                || AsyncTask.STATUS_CANCELLED.equals(status);
    }

    /** PENDING（排队/等待签署延后）→ CANCELLED 的条件更新；仅仍为 PENDING 时命中 1 行。 */
    private int cancelFromPending(Long id, LocalDateTime now) {
        AsyncTask update = new AsyncTask();
        update.setStatus(AsyncTask.STATUS_CANCELLED);
        update.setCancelRequested(true);
        update.setFinishedAt(now);
        update.setRetainUntil(now.plusDays(properties.getRetainDays()));
        update.setUpdatedAt(now);
        return taskMapper.update(update, new QueryWrapper<AsyncTask>()
                .eq("id", id)
                .eq("status", AsyncTask.STATUS_PENDING));
    }

    /** RUNNING 置取消标记的条件更新；仅仍为 RUNNING 时命中 1 行，执行器在下个步骤边界停下。 */
    private int flagRunning(Long id, LocalDateTime now) {
        AsyncTask update = new AsyncTask();
        update.setId(id);
        update.setCancelRequested(true);
        update.setUpdatedAt(now);
        return taskMapper.update(update, new QueryWrapper<AsyncTask>()
                .eq("id", id)
                .eq("status", AsyncTask.STATUS_RUNNING));
    }

    /**
     * 失败/取消任务断点重跑：已完成步骤不重复执行，从 currentStep+1 继续；
     * 平台侧写操作按业务幂等号去重，库内写入先查后写，重跑安全。
     */
    @Transactional(rollbackFor = Exception.class)
    public AsyncTask retry(String taskNo) {
        AsyncTask task = requireOwned(taskNo);
        if (!AsyncTask.STATUS_FAILED.equals(task.getStatus())
                && !AsyncTask.STATUS_CANCELLED.equals(task.getStatus())) {
            throw new IllegalArgumentException("仅失败或已取消的任务可以断点重跑，当前状态: " + task.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(AsyncTask.STATUS_PENDING);
        task.setCancelRequested(false);
        task.setFailStep(null);
        task.setFailReason(null);
        task.setLockedBy(null);
        task.setLockAt(null);
        task.setNextRunAt(now);
        task.setFinishedAt(null);
        task.setRetainUntil(null);
        task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        scopeGuard.audit("apartment:sign:retry", task.getRequestNo(),
                "taskNo=" + taskNo + ",resumeFromStep=" + (task.getCurrentStep() + 1)
                        + ",retryCount=" + task.getRetryCount(), null);
        return task;
    }

    // ---------------- 范围 ----------------

    private void applyTaskScope(QueryWrapper<AsyncTask> query) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            query.eq("operator", user.getUsername());
            return;
        }
        List<String> codes = scopeGuard.scopedPropertyCodes();
        if (codes.isEmpty()) {
            query.apply("1 = 0");
        } else {
            query.in("property_code", codes);
        }
    }

    private void requireVisible(AsyncTask task) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            if (!user.getUsername().equals(task.getOperator())) {
                throw new RbacForbiddenException("仅可查看本人发起的任务");
            }
            return;
        }
        if (!scopeGuard.scopedPropertyCodes().contains(task.getPropertyCode())) {
            throw new RbacForbiddenException("任务对应房源不在授权片区范围内");
        }
    }

    /** 取消/重跑仅任务发起人（SELF）或在范围内的角色可操作。 */
    private AsyncTask requireOwned(String taskNo) {
        AsyncTask task = taskMapper.selectOne(new QueryWrapper<AsyncTask>().eq("task_no", taskNo));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskNo);
        }
        requireVisible(task);
        return task;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("任务入参序列化失败", e);
        }
    }
}
