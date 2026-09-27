package com.evops.apartment.task.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.mapper.PropertyLedgerMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.apartment.sensitive.SensitiveGuard;
import com.evops.apartment.task.TaskProperties;
import com.evops.apartment.task.engine.SignArchiveStepHandler;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 取消与「等待签署退回（RUNNING→PENDING）」「调度认领（PENDING→RUNNING）」并发翻转时，
 * {@link AsyncTaskService#cancel(String)} 必须有界收敛、不得静默吞掉取消。
 *
 * 这里不引数据库/容器，纯 Mockito 精确编排条件更新的命中序列，锁定：
 * - 读到 RUNNING 置标落空（恰被退回 PENDING）→ 重读补 PENDING→CANCELLED，最终确为 CANCELLED；
 * - 读到 PENDING 取消落空（恰被领走 RUNNING）→ 重读改置 RUNNING 标记成功；
 * - 连续落空且最终为成功终态 → 明确抛错，不返回“看似取消”的假成功。
 */
class TaskCancelConvergenceTest {

    private AsyncTaskMapper taskMapper;
    private AsyncTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AsyncTaskMapper.class);
        TaskProperties properties = new TaskProperties();
        properties.setRetainDays(14);
        service = new AsyncTaskService(
                taskMapper,
                mock(PropertyLedgerMapper.class),
                mock(LeaseMapper.class),
                mock(ApartmentScopeGuard.class),
                mock(SensitiveGuard.class),
                mock(SignArchiveStepHandler.class),
                new ObjectMapper(),
                properties);
    }

    /** 缺陷主时序：RUNNING 置标 0 行（执行器刚退回 PENDING）→ 必须补一次 PENDING→CANCELLED 收敛。 */
    @Test
    void cancel_flagRunningMissesBecauseTaskDroppedToPending_convergesToCancelled() {
        AsyncTask loaded = runningTask(11, 55);
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(loaded);

        // attempt0 用入参 loaded（RUNNING）；attempt1 重读得到刚退回的 PENDING；收尾再读一次确认终态
        AsyncTask pendingView = viewOf(loaded, AsyncTask.STATUS_PENDING);
        AsyncTask cancelledView = viewOf(loaded, AsyncTask.STATUS_CANCELLED);
        // 模拟 cancelFromPending 在库里落终态：置取消标记与终态时间
        cancelledView.setCancelRequested(true);
        cancelledView.setFinishedAt(java.time.LocalDateTime.now());
        cancelledView.setRetainUntil(java.time.LocalDateTime.now().plusDays(14));
        when(taskMapper.selectById(7L)).thenReturn(pendingView, cancelledView);

        when(taskMapper.update(any(AsyncTask.class), any(Wrapper.class)))
                .thenAnswer((InvocationOnMock inv) -> {
                    AsyncTask entity = inv.getArgument(0);
                    // flagRunning 不写 status（null）；cancelFromPending 写 CANCELLED
                    if (AsyncTask.STATUS_CANCELLED.equals(entity.getStatus())) {
                        return 1; // 补的 PENDING→CANCELLED 命中
                    }
                    return 0;     // 第一次 RUNNING 置标落空（已退回 PENDING）
                });

        AsyncTask result = service.cancel("TASK-RACE-1");

        assertEquals(AsyncTask.STATUS_CANCELLED, result.getStatus());
        assertEquals(Boolean.TRUE, result.getCancelRequested());
        assertEquals(11, result.getCurrentStep(), "停在等待签署这个一致步骤边界（55%），进度不回退");
        assertEquals(55, result.getProgress());
        assertNotNull(result.getFinishedAt());
        assertNotNull(result.getRetainUntil());
    }

    /** 另一方向：PENDING 取消 0 行（恰被调度领走）→ 重读 RUNNING 后置标成功。 */
    @Test
    void cancel_pendingMissesBecauseClaimed_convergesByFlaggingRunning() {
        AsyncTask loaded = pendingTask(0, 0);
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(loaded);

        AsyncTask runningView = viewOf(loaded, AsyncTask.STATUS_RUNNING);
        AsyncTask finalRunning = viewOf(loaded, AsyncTask.STATUS_RUNNING);
        finalRunning.setCancelRequested(true);
        when(taskMapper.selectById(9L)).thenReturn(runningView, finalRunning);

        when(taskMapper.update(any(AsyncTask.class), any(Wrapper.class)))
                .thenAnswer((InvocationOnMock inv) -> {
                    AsyncTask entity = inv.getArgument(0);
                    if (AsyncTask.STATUS_CANCELLED.equals(entity.getStatus())) {
                        return 0; // PENDING→CANCELLED 落空（已被领走）
                    }
                    return 1;     // 随后 RUNNING 置标命中
                });

        AsyncTask result = service.cancel("TASK-RACE-2");

        // 标记已落库，执行器下一个步骤边界停下；返回的是标记后的 RUNNING 视图
        assertEquals(AsyncTask.STATUS_RUNNING, result.getStatus());
        assertEquals(Boolean.TRUE, result.getCancelRequested());
    }

    /** 置标落空后重读发现任务已成功：必须抛错，禁止返回假取消。 */
    @Test
    void cancel_alwaysLosesRaceAndEndsSuccess_throwsRatherThanPretendCancelled() {
        AsyncTask loaded = runningTask(19, 95);
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(loaded);
        AsyncTask success = viewOf(loaded, AsyncTask.STATUS_SUCCESS);
        success.setProgress(100);
        success.setCurrentStep(20);
        when(taskMapper.selectById(7L)).thenReturn(success);
        when(taskMapper.update(any(AsyncTask.class), any(Wrapper.class))).thenReturn(0);

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> service.cancel("TASK-RACE-3"));
        assertTrue(ex.getMessage().contains("SUCCESS") || ex.getMessage().contains("取消未生效"),
                "应明确告知取消未生效/已结束，不得假成功: " + ex.getMessage());
    }

    private AsyncTask runningTask(int step, int progress) {
        AsyncTask t = baseTask();
        t.setId(7L);
        t.setStatus(AsyncTask.STATUS_RUNNING);
        t.setCurrentStep(step);
        t.setProgress(progress);
        return t;
    }

    private AsyncTask pendingTask(int step, int progress) {
        AsyncTask t = baseTask();
        t.setId(9L);
        t.setStatus(AsyncTask.STATUS_PENDING);
        t.setCurrentStep(step);
        t.setProgress(progress);
        return t;
    }

    private AsyncTask baseTask() {
        AsyncTask t = new AsyncTask();
        t.setTaskNo("TASK-RACE");
        t.setTaskType(AsyncTask.TYPE_SIGN_ARCHIVE);
        t.setBizRef("HT-RACE");
        t.setPropertyCode("P-RACE");
        t.setTotalSteps(20);
        t.setCancelRequested(false);
        t.setRetryCount(0);
        t.setRequestNo("REQ-RACE");
        t.setOperator("steward01");
        return t;
    }

    private AsyncTask viewOf(AsyncTask src, String status) {
        AsyncTask v = new AsyncTask();
        v.setId(src.getId());
        v.setTaskNo(src.getTaskNo());
        v.setTaskType(src.getTaskType());
        v.setBizRef(src.getBizRef());
        v.setPropertyCode(src.getPropertyCode());
        v.setTotalSteps(src.getTotalSteps());
        v.setCurrentStep(src.getCurrentStep());
        v.setProgress(src.getProgress());
        v.setRequestNo(src.getRequestNo());
        v.setOperator(src.getOperator());
        v.setRetryCount(src.getRetryCount());
        v.setCancelRequested(src.getCancelRequested());
        v.setStatus(status);
        return v;
    }
}
