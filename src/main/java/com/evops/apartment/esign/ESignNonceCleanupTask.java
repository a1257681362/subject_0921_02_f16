package com.evops.apartment.esign;

import com.evops.apartment.esign.mapper.ESignNonceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 过期 nonce 记录清理：防重放只要求窗口（默认 15 分钟，见 {@code evops.esign.callback.nonce-window-minutes}）
 * 内唯一，窗口外的记录无保留价值，周期性删除避免表无限增长。只删 {@code expire_at < now}，
 * 不触碰窗口内记录。
 *
 * 回调验签关闭（应急）时也继续清理，无副作用；初始延迟避开启动高峰。
 */
@Component
public class ESignNonceCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(ESignNonceCleanupTask.class);

    private final ESignNonceMapper nonceMapper;

    public ESignNonceCleanupTask(ESignNonceMapper nonceMapper) {
        this.nonceMapper = nonceMapper;
    }

    @Scheduled(fixedDelayString = "${evops.esign.callback.nonce-cleanup-interval-ms:60000}",
               initialDelayString = "${evops.esign.callback.nonce-cleanup-initial-delay-ms:30000}")
    public void cleanup() {
        try {
            int deleted = nonceMapper.deleteExpired(LocalDateTime.now());
            if (deleted > 0) {
                log.debug("清理过期电子签章回调 nonce 记录 {} 条", deleted);
            }
        } catch (Exception e) {
            // 清理失败不影响验签主链路（窗口内记录仍在，防重放不受影响），下轮再试
            log.warn("电子签章回调 nonce 清理异常: {}", e.getMessage());
        }
    }

    /** 供需要确定性清理的测试直接调用。 */
    public int cleanupNow() {
        return nonceMapper.deleteExpired(LocalDateTime.now());
    }
}
