package com.evops.apartment.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.apartment.quota.entity.QuotaHold;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预占单状态流转全部走条件更新（CAS）：
 * 主动取消与后台超时释放可能并发抢同一单，只有一个 WHERE status='HELD' 成功，
 * 账上 reserved 因此也只回补一次（CAS 0 行的一方不再动账、不写流水）。
 */
@Mapper
public interface QuotaHoldMapper extends BaseMapper<QuotaHold> {

    /** HELD → CONFIRMED。仅预占中可确认（是否已过 expires_at 由服务层判定后决定是否放行）。 */
    @Update("UPDATE t_quota_hold SET status = 'CONFIRMED', confirmed_at = #{now}, " +
            "data_version = data_version + 1, updated_at = #{now} " +
            "WHERE id = #{id} AND status = 'HELD'")
    int casConfirm(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** HELD → RELEASED（主动取消或超时释放；reason 区分，时间由调用方传入）。 */
    @Update("UPDATE t_quota_hold SET status = 'RELEASED', release_reason = #{reason}, " +
            "released_at = #{now}, data_version = data_version + 1, updated_at = #{now} " +
            "WHERE id = #{id} AND status = 'HELD'")
    int casRelease(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** 后台扫描：取一批已到期仍 HELD 的预占单 id（加 ROWS 上限，单轮可控）。 */
    @Select("SELECT id FROM t_quota_hold WHERE status = 'HELD' AND expires_at <= #{now} " +
            "ORDER BY expires_at, id FETCH FIRST #{limit} ROWS ONLY")
    List<Long> findExpiredIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
