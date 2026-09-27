package com.evops.apartment.task.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import com.evops.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台工作线程 MDC 隔离回归（apt-15）。
 *
 * 复现“一个任务的日志混进别人的单号”：工作线程池化复用，任务在线程上开始执行时，
 * 线程可能已带着上下文（线程池装饰器传播、CallerRuns 回退到带上下文的线程、
 * 上一单清理未执行）。执行器必须在认领时<b>无条件清空再按任务表重绑</b>，
 * 且重绑是权威写——任务没有合同号（biz_ref 为空）时摘除残留，而不是沿用上一单。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_trace_isolation_test;DB_CLOSE_DELAY=-1",
        "evops.task.enabled=false",
        "evops.esign.mock.step-delay-ms=0"
})
@Import(WorkerMdcIsolationFlowTest.IsolationTestConfig.class)
class WorkerMdcIsolationFlowTest {

    static final String TASK_TYPE = "TRACE_ISOLATION_TEST";

    @Autowired private TaskExecutor taskExecutor;
    @Autowired private AsyncTaskMapper taskMapper;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void tearDown() {
        appender.stop();
        ((Logger) LoggerFactory.getLogger(TASK_TYPE)).detachAppender(appender);
        TraceContext.clear();
    }

    @Test
    void claimWipesAmbientMdc_andTaskWithoutContractDoesNotInheritStaleOne() {
        Logger taskLogger = (Logger) LoggerFactory.getLogger(TASK_TYPE);
        appender.start();
        taskLogger.addAppender(appender);

        // 任务本身没有合同号（biz_ref 允许为空：预留任务类型/历史脏数据），但有自己的 traceId
        Long taskBId = insertTask("TASK-ISO-B-" + uniqueSuffix(), "trace-worker-B-0002", null, "opB");

        // 模拟池化工作线程被认领时仍带着“上一单/外部传播”的完整上下文
        TraceContext.setTraceId("trace-STALE-ambient");
        TraceContext.bindContract("HT-STALE-AMBIENT");
        TraceContext.setUsername("staleUser");

        taskExecutor.claimAndRun(taskBId);

        AsyncTask done = taskMapper.selectById(taskBId);
        assertEquals(AsyncTask.STATUS_SUCCESS, done.getStatus(),
                "处理器内 MDC 断言失败会使任务 FAILED：" + done.getFailReason());

        // 步骤内打出的日志：traceId 必须是本任务的；合同号必须为空，绝不能是残留的 HT-STALE-AMBIENT
        List<ILoggingEvent> events = appender.list;
        assertFalse(events.isEmpty(), "测试处理器应至少落一条日志");
        for (ILoggingEvent e : events) {
            assertEquals("trace-worker-B-0002", e.getMDCPropertyMap().get(TraceContext.TRACE_ID),
                    "工作线程日志必须带本任务的 TraceId");
            assertNull(e.getMDCPropertyMap().get(TraceContext.CONTRACT_NO),
                    "任务无合同号时必须摘除残留合同号，不得把本单日志挂到别人单号下");
            assertEquals("opB", e.getMDCPropertyMap().get(TraceContext.USERNAME));
        }

        // 执行结束工作线程 MDC 清空，下一个被该线程认领的任务从干净状态开始
        assertNull(MDC.get(TraceContext.TRACE_ID));
        assertNull(MDC.get(TraceContext.CONTRACT_NO));
        assertNull(MDC.get(TraceContext.USERNAME));
    }

    @Test
    void twoTasksOnSameThread_eachLogsUnderOwnTraceAndContract() {
        Logger taskLogger = (Logger) LoggerFactory.getLogger(TASK_TYPE);
        appender.start();
        taskLogger.addAppender(appender);

        Long idA = insertTask("TASK-ISO-A-" + uniqueSuffix(), "trace-worker-A-0001", "HT-WORKER-A", "opA");
        Long idB = insertTask("TASK-ISO-B2-" + uniqueSuffix(), "trace-worker-B-0001", "HT-WORKER-B", "opB");

        // 同一线程顺序认领（池化复用的直接模拟）：各单日志只能带自己的 traceId/合同号
        taskExecutor.claimAndRun(idA);
        taskExecutor.claimAndRun(idB);

        assertEquals(AsyncTask.STATUS_SUCCESS, taskMapper.selectById(idA).getStatus());
        assertEquals(AsyncTask.STATUS_SUCCESS, taskMapper.selectById(idB).getStatus());

        boolean sawA = false, sawB = false;
        for (ILoggingEvent e : appender.list) {
            String trace = e.getMDCPropertyMap().get(TraceContext.TRACE_ID);
            String contract = e.getMDCPropertyMap().get(TraceContext.CONTRACT_NO);
            if ("trace-worker-A-0001".equals(trace)) {
                sawA = true;
                assertEquals("HT-WORKER-A", contract, "A 单日志不得混入 B 单合同号");
            } else if ("trace-worker-B-0001".equals(trace)) {
                sawB = true;
                assertEquals("HT-WORKER-B", contract, "B 单日志不得混入 A 单合同号");
            }
        }
        assertTrue(sawA && sawB, "两个任务的步骤日志都应被采集到");
    }

    private Long insertTask(String taskNo, String traceId, String bizRef, String operator) {
        AsyncTask t = new AsyncTask();
        t.setTaskNo(taskNo);
        t.setTaskType(TASK_TYPE);
        t.setBizRef(bizRef);
        t.setTraceId(traceId);
        t.setStatus(AsyncTask.STATUS_PENDING);
        t.setProgress(0);
        t.setCurrentStep(0);
        t.setTotalSteps(1);
        t.setCancelRequested(false);
        t.setRetryCount(0);
        t.setPayload("{}");
        t.setRequestNo("REQ-" + taskNo);
        t.setOperator(operator);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(t);
        return t.getId();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 单步测试处理器：执行时以任务表存储值为基准校验当前线程 MDC，
     * 不一致直接抛 AssertionError（执行器兜底置 FAILED，测试据此失败），并落一条 INFO 供 appender 断言。
     */
    static class IsolationTestStepHandler implements TaskStepHandler {
        private static final org.slf4j.Logger log = LoggerFactory.getLogger(TASK_TYPE);

        @Override
        public String type() {
            return TASK_TYPE;
        }

        @Override
        public int totalSteps() {
            return 1;
        }

        @Override
        public StepResult executeStep(AsyncTask task, int stepNo) {
            assertEquals(task.getTraceId(), TraceContext.getTraceId(),
                    "工作线程 MDC traceId 与任务表不一致（串号）");
            String mdcContract = MDC.get(TraceContext.CONTRACT_NO);
            if (task.getBizRef() == null) {
                assertNull(mdcContract, "任务无 bizRef，MDC 不得残留上一任务的合同号: " + mdcContract);
            } else {
                assertEquals(task.getBizRef(), mdcContract, "工作线程 MDC 合同号与任务表不一致");
            }
            assertEquals(task.getOperator(), MDC.get(TraceContext.USERNAME));
            log.info("隔离测试任务步骤执行 taskNo={} step={}", task.getTaskNo(), stepNo);
            return StepResult.finished("{\"ok\":true}");
        }
    }

    @TestConfiguration
    static class IsolationTestConfig {
        @Bean
        IsolationTestStepHandler isolationTestStepHandler() {
            return new IsolationTestStepHandler();
        }
    }
}
