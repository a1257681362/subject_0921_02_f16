package com.evops.apartment.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.apartment.task.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 任务认领与状态流转走条件更新（CAS），保证多调度线程/多节点下同一任务只被一个执行者领取。
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    /**
     * 认领一个到期 PENDING 任务：仅当仍为 PENDING 且到 next_run_at 时置 RUNNING。
     * （签署等待等场景把 next_run_at 推到未来，避免空转轮询平台。）
     */
    @Update("UPDATE t_async_task SET status = 'RUNNING', locked_by = #{lockedBy}, lock_at = #{now}, " +
            "started_at = COALESCE(started_at, #{now}), updated_at = #{now} " +
            "WHERE id = #{id} AND status = 'PENDING' " +
            "AND (next_run_at IS NULL OR next_run_at <= #{now})")
    int claim(@Param("id") Long id, @Param("lockedBy") String lockedBy, @Param("now") LocalDateTime now);

    /** 宕机回收：启动时把无主 RUNNING 退回 PENDING 等续跑（步骤幂等）。 */
    @Update("UPDATE t_async_task SET status = 'PENDING', locked_by = NULL, lock_at = NULL, updated_at = #{now} " +
            "WHERE status = 'RUNNING'")
    int recoverRunning(@Param("now") LocalDateTime now);

    /**
     * apt-9 平台签署完成回调即时唤醒：把停在第 12 步「等待租客签署」（已完成 11 步）的
     * PENDING 任务的下次认领时间提到当前，使其下一轮派发即被领取、立即继续盖章/归档，
     * 不必等满一个轮询间隔。CAS 条件限定 PENDING+第 11 步边界：
     * - 任务已被领取（RUNNING）或已越过等待步（current_step&gt;11）：0 行，不回退进度，由轮询/在途执行兜底；
     * - 任务尚未走到签署步（current_step&lt;11）：不会被回调提前唤醒。
     * 同合同进行中任务唯一（提交接口已拦重复），更新最多命中一行。
     */
    @Update("UPDATE t_async_task SET next_run_at = #{now}, updated_at = #{now} " +
            "WHERE task_type = 'SIGN_ARCHIVE' AND status = 'PENDING' AND current_step = 11 " +
            "AND biz_ref = #{contractNo}")
    int wakeWaitingForSign(@Param("contractNo") String contractNo, @Param("now") LocalDateTime now);

    /** 删除过保留期的终态任务记录（默认保留 14 天）。 */
    @Update("DELETE FROM t_async_task WHERE retain_until IS NOT NULL AND retain_until < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
