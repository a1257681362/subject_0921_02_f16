package com.evops.apartment.esign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.apartment.esign.entity.ESignNonce;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 回调 nonce 记录读写。去重靠表上 {@code uk_esign_nonce_nonce} 唯一约束：
 * 抢占插入冲突即判重放；窗口到期记录由 {@link #deleteExpired} 清理。
 */
@Mapper
public interface ESignNonceMapper extends BaseMapper<ESignNonce> {

    /** 删除防重放窗口已过的 nonce 记录（默认 15 分钟，定时扫描）。 */
    @Update("DELETE FROM t_esign_nonce WHERE expire_at < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
