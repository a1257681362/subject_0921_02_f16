package com.evops.common.opslog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 运维日志采集与保留：查询走 MyBatis-Plus QueryWrapper（服务层动态拼条件），
 * 14 天滚动删除走固定 SQL。
 */
@Mapper
public interface OpsLogEntryMapper extends BaseMapper<OpsLogEntry> {

    /** 删除 14 天保留期之前的采集日志。 */
    @Update("DELETE FROM t_ops_log_entry WHERE log_time < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
