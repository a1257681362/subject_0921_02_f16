package com.evops.apartment.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.apartment.quota.entity.QuotaBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 额度账写操作全部走条件更新（CAS），把“检查余量 + 扣减”压成一条原子 SQL：
 * 并发申请时数据库行锁串行化，WHERE 余量不足则更新 0 行，绝不超扣。
 */
@Mapper
public interface QuotaBalanceMapper extends BaseMapper<QuotaBalance> {

    /**
     * 预占：仅当余量（total - occupied - reserved）&gt;= amount 时 reserved += amount。
     * 返回受影响行数：1=预占成功，0=额度账不存在或余量不足。
     */
    @Update("UPDATE t_quota_balance SET reserved_amount = reserved_amount + #{amount}, " +
            "request_no = #{requestNo}, operator = #{operator}, biz_time = #{now}, " +
            "data_version = data_version + 1, updated_at = #{now} " +
            "WHERE community = #{community} " +
            "AND total_amount - occupied_amount - reserved_amount >= #{amount}")
    int reserve(@Param("community") String community, @Param("amount") int amount,
                @Param("requestNo") String requestNo, @Param("operator") String operator,
                @Param("now") java.time.LocalDateTime now);

    /**
     * 确认实扣：occupied += amount 且 reserved -= amount（预占转实扣，total 不变）。
     * 仅当预占额足够时成功（正常恒成立，防御性条件）。
     */
    @Update("UPDATE t_quota_balance SET occupied_amount = occupied_amount + #{amount}, " +
            "reserved_amount = reserved_amount - #{amount}, " +
            "request_no = #{requestNo}, operator = #{operator}, biz_time = #{now}, " +
            "data_version = data_version + 1, updated_at = #{now} " +
            "WHERE community = #{community} AND reserved_amount >= #{amount}")
    int confirm(@Param("community") String community, @Param("amount") int amount,
                @Param("requestNo") String requestNo, @Param("operator") String operator,
                @Param("now") java.time.LocalDateTime now);

    /** 释放预占：reserved -= amount，仅当预占额足够时成功。 */
    @Update("UPDATE t_quota_balance SET reserved_amount = reserved_amount - #{amount}, " +
            "request_no = #{requestNo}, operator = #{operator}, biz_time = #{now}, " +
            "data_version = data_version + 1, updated_at = #{now} " +
            "WHERE community = #{community} AND reserved_amount >= #{amount}")
    int release(@Param("community") String community, @Param("amount") int amount,
                @Param("requestNo") String requestNo, @Param("operator") String operator,
                @Param("now") java.time.LocalDateTime now);

    /** 配额调整：仅当新配额不低于“已占用 + 预占中”（不击穿在途单）时生效。 */
    @Update("UPDATE t_quota_balance SET total_amount = #{newTotal}, " +
            "request_no = #{requestNo}, operator = #{operator}, biz_time = #{now}, " +
            "data_version = data_version + 1, updated_at = #{now} " +
            "WHERE community = #{community} AND #{newTotal} >= occupied_amount + reserved_amount")
    int adjustTotal(@Param("community") String community, @Param("newTotal") int newTotal,
                    @Param("requestNo") String requestNo, @Param("operator") String operator,
                    @Param("now") java.time.LocalDateTime now);

    /** 对账用：库内 HELD 预占单的预占额合计，应与 balance.reserved_amount 恒等。 */
    @Select("SELECT COALESCE(SUM(reserve_amount), 0) FROM t_quota_hold WHERE community = #{community} AND status = 'HELD'")
    int sumHeldReserved(@Param("community") String community);
}
