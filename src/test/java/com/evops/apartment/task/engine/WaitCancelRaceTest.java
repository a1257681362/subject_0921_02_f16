package com.evops.apartment.task.engine;

import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性复现并锁定「等待租客签署」边界与取消并发的缺陷，不依赖线程时序：
 * 用一个两步测试处理器，在等待步（第 2 步）的步骤体执行期间把 cancel_requested 落库，
 * 精确等价于“取消在第 12 步执行中到达”，随后该步返回 WAIT。
 *
 * 修复前：backToPending 无条件把 RUNNING 降回 PENDING，取消被吞，任务会被重新捞取续跑；
 * 修复后：CAS 退回失败，执行器就地落 CANCELLED，停在当前一致步骤边界（进度只增不减）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_cancel_race_test;DB_CLOSE_DELAY=-1",
        "evops.task.enabled=false"
})
class WaitCancelRaceTest {

    static final String TYPE = "WAIT_CANCEL_RACE";

    /** 等待步是否在步骤体执行期间模拟取消到达；每个用例前重置。 */
    static volatile boolean cancelDuringWait = true;

    @TestConfiguration
    static class RaceHandlerConfig {
        @Bean
        TaskStepHandler waitCancelRaceHandler(AsyncTaskMapper mapper) {
            return new TaskStepHandler() {
                @Override
                public String type() {
                    return TYPE;
                }

                @Override
                public int totalSteps() {
                    return 2;
                }

                @Override
                public StepResult executeStep(AsyncTask task, int stepNo) {
                    if (stepNo == 1) {
                        return StepResult.advance();
                    }
                    if (cancelDuringWait) {
                        // 等价于取消请求恰在“等待签署”这一步执行期间落库
                        AsyncTask flag = new AsyncTask();
                        flag.setId(task.getId());
                        flag.setCancelRequested(true);
                        mapper.updateById(flag);
                    }
                    return StepResult.waiting();
                }
            };
        }
    }

    @Autowired private TaskExecutor executor;
    @Autowired private AsyncTaskMapper taskMapper;

    @BeforeEach
    void resetFlag() {
        cancelDuringWait = true;
    }

    /** 主缺陷时序：等待步执行期间取消到达 → 就地 CANCELLED，绝不退回可被捞取的 PENDING。 */
    @Test
    void cancelArrivesDuringWaitStep_stopsCancelledAndNeverBackToPending() {
        Long id = insertPendingTask(0, 0, false);
        int progressBefore = 50; // 第 1 步完成后应为 50%

        executor.claimAndRun(id);

        AsyncTask done = taskMapper.selectById(id);
        assertEquals(AsyncTask.STATUS_CANCELLED, done.getStatus(),
                "等待期间已申请取消，必须就地落 CANCELLED，不得降回 PENDING");
        assertEquals(Boolean.TRUE, done.getCancelRequested());
        assertEquals(1, done.getCurrentStep(), "停在已提交的第 1 步这个一致边界");
        assertEquals(progressBefore, done.getProgress(), "进度停在 50%，绝不回退");

        // 已取消任务不会被到期扫描捞起，也不会因再次认领而续跑
        assertFalse(executor.findDue(100).contains(id), "CANCELLED 任务不应出现在到期待认领集合");
        executor.claimAndRun(id); // 即便误投递，非 PENDING 直接退出，状态不变
        AsyncTask after = taskMapper.selectById(id);
        assertEquals(AsyncTask.STATUS_CANCELLED, after.getStatus());
        assertEquals(1, after.getCurrentStep(), "再次认领不得续跑、不得推进步骤");
        assertEquals(progressBefore, after.getProgress(), "进度只增不减");
    }

    /** 对照：等待步期间无人取消 → 正常退回 PENDING 延后重领（修复未误伤正常等待路径）。 */
    @Test
    void noCancelDuringWaitStep_backToPendingNormally() {
        cancelDuringWait = false;
        Long id = insertPendingTask(0, 0, false);

        executor.claimAndRun(id);

        AsyncTask waiting = taskMapper.selectById(id);
        assertEquals(AsyncTask.STATUS_PENDING, waiting.getStatus(), "无取消时正常退回 PENDING 等待");
        assertEquals(1, waiting.getCurrentStep());
        assertEquals(50, waiting.getProgress());
        assertTrue(executor.findDue(100).contains(id)
                        || waiting.getNextRunAt() != null,
                "等待任务带下次认领时间，到点可被重领");
    }

    /**
     * 兜底路径：任务在 PENDING 等待态时取消标记已置位（排队/等待中取消的标记先落、
     * 状态翻转由另一处完成），即便被调度 claim 成 RUNNING，循环顶部也必须立即在边界停，
     * 一步都不执行。
     */
    @Test
    void pendingWithCancelFlag_whenClaimed_stopsAtBoundaryWithoutRunningStep() {
        Long id = insertPendingTask(1, 50, true); // 已完成第 1 步、带取消标记的等待任务

        executor.claimAndRun(id);

        AsyncTask done = taskMapper.selectById(id);
        assertEquals(AsyncTask.STATUS_CANCELLED, done.getStatus());
        assertEquals(1, done.getCurrentStep(), "已置取消标记，认领后不得再执行第 2 步");
        assertEquals(50, done.getProgress(), "进度只增不减，停在一致边界");
        assertFalse(executor.findDue(100).contains(id));
    }

    private Long insertPendingTask(int currentStep, int progress, boolean cancelRequested) {
        AsyncTask t = new AsyncTask();
        t.setTaskNo("TASK-CANCEL-RACE-" + System.nanoTime());
        t.setTaskType(TYPE);
        t.setBizRef("HT-CANCEL-RACE");
        t.setPropertyCode("P-CANCEL-RACE");
        t.setStatus(AsyncTask.STATUS_PENDING);
        t.setProgress(progress);
        t.setCurrentStep(currentStep);
        t.setTotalSteps(2);
        t.setCancelRequested(cancelRequested);
        t.setRetryCount(0);
        t.setPayload("{}");
        t.setRequestNo("REQ-CANCEL-RACE");
        t.setOperator("steward01");
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(t);
        return t.getId();
    }
}
