package com.evops.apartment.task.engine;

import com.evops.apartment.task.TaskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 后台任务调度：
 *
 * - 启动就绪后回收上次进程遗留的 RUNNING 任务（→ PENDING 断点续跑）；
 * - 固定间隔扫描到期 PENDING，投递给工作线程池执行；本机在途任务不重复投递，
 *   跨实例/跨线程由任务表 CAS 认领兜底，同一任务只有一个执行者；
 * - 每天清理超过保留期（14 天）的终态任务记录。
 */
@Component
public class TaskDispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatchScheduler.class);

    private final TaskProperties properties;
    private final TaskExecutor taskExecutor;
    private final Executor taskWorkerPool;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public TaskDispatchScheduler(TaskProperties properties,
                                 TaskExecutor taskExecutor,
                                 Executor taskWorkerPool) {
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.taskWorkerPool = taskWorkerPool;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.isEnabled()) {
            log.warn("后台任务调度已关闭（evops.task.enabled=false），任务仅登记不执行");
            return;
        }
        taskExecutor.recoverOrphaned();
    }

    /** 派发扫描。间隔可配，默认 2s；慢操作场景足够，前端轮询看到的是步骤级进度。 */
    @Scheduled(fixedDelayString = "${evops.task.dispatch-interval-ms:2000}",
               initialDelayString = "${evops.task.dispatch-initial-delay-ms:500}")
    public void dispatch() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int freeSlots = properties.getBatchSize() - inFlight.size();
            if (freeSlots <= 0) {
                return;
            }
            List<Long> due = taskExecutor.findDue(freeSlots * 2);
            int submitted = 0;
            for (Long id : due) {
                if (submitted >= freeSlots) {
                    break;
                }
                if (!inFlight.add(id)) {
                    continue; // 本机已在途
                }
                submitted++;
                try {
                    taskWorkerPool.execute(() -> {
                        try {
                            taskExecutor.claimAndRun(id);
                        } finally {
                            inFlight.remove(id);
                        }
                    });
                } catch (RuntimeException e) {
                    inFlight.remove(id);
                    log.warn("任务 {} 投递失败，等下轮扫描: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            // 调度异常不影响下一轮
            log.error("后台任务派发扫描异常", e);
        }
    }

    /** 每日 01:17 清理过期终态任务记录（保留 14 天）。 */
    @Scheduled(cron = "${evops.task.cleanup-cron:0 17 1 * * ?}")
    public void cleanup() {
        try {
            int deleted = taskExecutor.deleteExpired();
            if (deleted > 0) {
                log.info("清理超过 {} 天保留期的任务记录 {} 条", properties.getRetainDays(), deleted);
            }
        } catch (Exception e) {
            log.error("过期任务清理异常", e);
        }
    }
}
