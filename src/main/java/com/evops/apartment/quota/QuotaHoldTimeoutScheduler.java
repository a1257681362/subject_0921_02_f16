package com.evops.apartment.quota;

import com.evops.apartment.quota.mapper.QuotaHoldMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预占超时扫描：固定间隔查找已到期（申请后 20 分钟未确认）仍 HELD 的预占单，
 * 逐单在独立事务内条件释放（{@link QuotaService#releaseIfExpired(Long)}）。
 *
 * 多实例/多轮扫描并发安全：释放是 WHERE status='HELD' 的条件更新，
 * 同一单只有一个执行者成功并回补账上预占额，其余 0 行空转。
 * 主动取消恰与扫描并发时同理——只释放一次、账只回补一次。
 */
@Component
public class QuotaHoldTimeoutScheduler {
    private static final Logger log = LoggerFactory.getLogger(QuotaHoldTimeoutScheduler.class);
    private static final int BATCH_LIMIT = 200;

    private final QuotaProperties properties;
    private final QuotaHoldMapper holdMapper;
    private final QuotaService quotaService;

    public QuotaHoldTimeoutScheduler(QuotaProperties properties,
                                     QuotaHoldMapper holdMapper,
                                     QuotaService quotaService) {
        this.properties = properties;
        this.holdMapper = holdMapper;
        this.quotaService = quotaService;
    }

    @Scheduled(fixedDelayString = "${evops.quota.sweep-interval-ms:30000}",
               initialDelayString = "${evops.quota.sweep-initial-delay-ms:10000}")
    public void sweepExpired() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Long> expiredIds = holdMapper.findExpiredIds(now, BATCH_LIMIT);
            int released = 0;
            for (Long id : expiredIds) {
                try {
                    if (quotaService.releaseIfExpired(id)) {
                        released++;
                    }
                } catch (Exception e) {
                    // 单笔释放失败不影响同轮其他单；下一轮会再次扫描到（仍是 HELD）
                    log.warn("预占单 {} 超时释放失败，留待下轮扫描: {}", id, e.getMessage());
                }
            }
            if (released > 0) {
                log.info("超时预占扫描完成：本轮自动释放 {} 笔（扫描 {} 笔到期）", released, expiredIds.size());
            }
        } catch (Exception e) {
            // 调度异常不影响下一轮
            log.error("预占超时扫描异常", e);
        }
    }
}
