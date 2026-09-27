package com.evops.apartment.task;

import com.evops.common.trace.MdcTaskDecorator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 后台任务装配：开启 Spring 调度（任务派发扫描与保留期清理），
 * 提供与业务请求隔离的任务工作线程池（满则由调用线程执行，形成背压不丢任务）。
 * Quartz starter 仍在依赖中，本期慢任务调度用更轻的 fixed-delay 扫描 + DB CAS 认领。
 *
 * apt-6：工作池装配 {@link MdcTaskDecorator}，投递点已有的 MDC（如调度链路）自动带入工作线程；
 * 任务自身的 traceId/合同号由执行器按任务表存储在工作线程内重新绑定（见 TaskExecutor）。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(TaskProperties.class)
public class TaskConfig {

    @Bean(name = "taskWorkerPool")
    public Executor taskWorkerPool(TaskProperties properties,
                                   ObjectProvider<MdcTaskDecorator> mdcTaskDecorator) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        int size = Math.max(1, properties.getBatchSize());
        pool.setCorePoolSize(size);
        pool.setMaxPoolSize(size);
        pool.setQueueCapacity(size * 8);
        pool.setThreadNamePrefix("async-task-");
        mdcTaskDecorator.ifAvailable(pool::setTaskDecorator);
        // 池满时由调度线程自己跑：天然背压，任务不丢
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(20);
        pool.initialize();
        return pool;
    }
}

