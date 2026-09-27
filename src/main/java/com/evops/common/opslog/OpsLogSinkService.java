package com.evops.common.opslog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 采集日志异步入库 Sink：
 *
 * - 业务日志线程只执行 {@link #offer}（有界队列，满即丢弃并计数），不碰数据库、不被 IO 拖慢；
 * - 单条守护线程按固定节奏（最多 1 秒延迟）成批落 t_ops_log_entry；
 * - 14 天保留：每日 01:23 清理；
 * - 落库失败只计数不走日志框架（防止“落库失败→打日志→再次落库失败”的自激循环）。
 */
@Service
public class OpsLogSinkService implements OpsLogSink {
    /** 自保护：落库失败的告警走 stderr 而不是 slf4j，避免采集自激递归。 */
    private static final Logger selfLog = LoggerFactory.getLogger(OpsLogSinkService.class);

    private final OpsLogEntryMapper mapper;
    private final OpsLogProperties properties;
    private final BlockingQueue<OpsLogRecord> queue;

    private volatile boolean running;
    private Thread worker;
    private long enqueueFailed;
    private long persistFailed;

    public OpsLogSinkService(OpsLogEntryMapper mapper, OpsLogProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(Math.max(100, properties.getQueueCapacity()));
    }

    @PostConstruct
    public void start() {
        OpsDbAppender.setCollectEnabled(properties.isEnabled());
        OpsLogSinkRegistry.register(this);
        if (!properties.isEnabled()) {
            selfLog.warn("运维日志 DB 采集已关闭（evops.ops-log.enabled=false），文件日志不受影响");
            return;
        }
        running = true;
        worker = new Thread(this::drainLoop, "ops-log-sink");
        worker.setDaemon(true);
        worker.start();
        selfLog.info("运维日志采集已启动：有界队列 {}，库内保留 {} 天",
                properties.getQueueCapacity(), properties.getRetainDays());
    }

    @PreDestroy
    public void stop() {
        OpsLogSinkRegistry.clear();
        running = false;
        if (worker != null) {
            try {
                worker.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 关闭前尽量把队列里的日志落完（最多再等一轮）
        flushBatch();
    }

    @Override
    public boolean offer(OpsLogRecord record) {
        if (!properties.isEnabled()) {
            return true; // 采集关闭：交由 appender 侧开关拦截，这里不计丢弃
        }
        boolean ok = queue.offer(record);
        if (!ok) {
            enqueueFailed++;
        }
        return ok;
    }

    /** 每日 01:23 删除超过 14 天保留期的采集日志。 */
    @Scheduled(cron = "${evops.ops-log.cleanup-cron:0 23 1 * * ?}")
    public void cleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetainDays());
            int deleted = mapper.deleteOlderThan(cutoff);
            if (deleted > 0) {
                selfLog.info("清理超过 {} 天保留期的运维日志 {} 条", properties.getRetainDays(), deleted);
            }
        } catch (Exception e) {
            // 不走 slf4j 业务 logger 递归；selfLog 自身 WARN 仍可能入队（队列丢弃兜底）
            System.err.println("[ops-log] cleanup failed: " + e);
        }
    }

    public long getEnqueueFailed() { return enqueueFailed; }
    public long getPersistFailed() { return persistFailed; }
    public int getQueueSize() { return queue.size(); }

    private void drainLoop() {
        while (running) {
            try {
                OpsLogRecord first = queue.poll(properties.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<OpsLogRecord> batch = new ArrayList<>(properties.getFlushBatchSize());
                batch.add(first);
                queue.drainTo(batch, properties.getFlushBatchSize() - 1);
                persist(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 工作线程不能死：任何异常打印后继续下一轮
                System.err.println("[ops-log] drain loop error: " + t);
            }
        }
        flushBatch();
    }

    private void flushBatch() {
        List<OpsLogRecord> batch = new ArrayList<>(properties.getFlushBatchSize());
        queue.drainTo(batch, properties.getFlushBatchSize());
        if (!batch.isEmpty()) {
            persist(batch);
        }
    }

    private void persist(List<OpsLogRecord> batch) {
        for (OpsLogRecord r : batch) {
            try {
                mapper.insert(toEntity(r));
            } catch (Exception e) {
                persistFailed++;
                System.err.println("[ops-log] persist failed, drop one: " + e.getMessage());
            }
        }
    }

    private OpsLogEntry toEntity(OpsLogRecord r) {
        OpsLogEntry e = new OpsLogEntry();
        e.setLogTime(r.getLogTime());
        e.setLevel(r.getLevel());
        e.setLoggerName(r.getLoggerName());
        e.setThreadName(r.getThreadName());
        e.setTraceId(r.getTraceId());
        e.setContractNo(r.getContractNo());
        e.setUsername(r.getUsername());
        e.setMessage(r.getMessage());
        e.setStack(r.getStack());
        return e;
    }
}
