package com.evops.apartment.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.apartment.quota.entity.QuotaJournal;
import org.apache.ibatis.annotations.Mapper;

/**
 * 额度流水账：只增不改。复杂检索用 QueryWrapper 在服务层组装（叠加数据范围）。
 */
@Mapper
public interface QuotaJournalMapper extends BaseMapper<QuotaJournal> {
}
