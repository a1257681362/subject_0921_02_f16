package com.evops.apartment.sensitive;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SensitiveAuditMapper extends BaseMapper<SensitiveAudit> {
}
