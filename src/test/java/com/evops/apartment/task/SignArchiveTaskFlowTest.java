package com.evops.apartment.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.esign.MockESignPlatformClient;
import com.evops.apartment.task.engine.SignArchiveStepHandler;
import com.evops.apartment.task.engine.TaskExecutor;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.entity.LeaseSignArchive;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import com.evops.apartment.task.mapper.LeaseSignArchiveMapper;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.LeaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-5 签约归档后台任务端到端：
 * 1. 发起即返回 → 轮询进度按 5% 步进 → 电子签章（mock）走完 → SUCCESS 100%，租约/归档/文件齐备；
 * 2. 平台故障：任务 FAILED 停在断点步骤，清故障后断点重跑成功，租约/归档/平台流水均不重复；
 * 3. 等租客签署（PENDING 延后）时取消，停在最近一致步骤（55%），重跑继续成功；
 * 4. 敏感词：拦截级发起失败不建任务，打码级入任务文本已打码；
 * 5. 数据范围：SELF 仅本人，SCOPE 限授权小区，越区 403；
 * 6. 宕机回收：遗留 RUNNING 启动口径回收后续跑成功；
 * 7. 14 天保留期清理只删过期终态记录。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_apt5_test;DB_CLOSE_DELAY=-1",
        "evops.task.dispatch-interval-ms=100",
        "evops.task.dispatch-initial-delay-ms=100",
        "evops.task.sign-poll-interval-ms=1000",
        "evops.esign.mock.step-delay-ms=0",
        "evops.esign.mock.sign-wait-ms=400"
})
@AutoConfigureMockMvc
class SignArchiveTaskFlowTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockESignPlatformClient mockESign;
    @Autowired private AsyncTaskMapper taskMapper;
    @Autowired private LeaseMapper leaseMapper;
    @Autowired private LeaseSignArchiveMapper archiveMapper;
    @Autowired private AsyncTaskMapper asyncTaskMapper;
    @Autowired private TaskExecutor taskExecutor;
    @Autowired private TaskProperties taskProperties;

    private String stewardToken;
    private String supervisorToken;
    private String areaToken;

    @BeforeEach
    void setUp() throws Exception {
        mockESign.resetForTest();
        stewardToken = login("steward01", "steward123");
        supervisorToken = login("supervisor01", "supervisor123");
        areaToken = login("area01", "area123");
    }

    @Test
    void fullFlow_progressByFivePercent_thenSuccessWithArchiveAndFile() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-001", "示例小区");
        String contractNo = "HT-SIGN-FULL-0001";

        MvcResult submitted = mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken)
                        .contentType("application/json")
                        .content(submitJson(contractNo, "PROP-SIGN-001", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.progress").value(0))
                .andExpect(jsonPath("$.data.totalSteps").value(20))
                .andReturn();
        String taskNo = readNode(submitted).path("data").path("taskNo").asText();

        // 轮询过程中收集进度：必须单调递增且恒为 5 的整数倍
        int last = -1;
        JsonNode data = null;
        long deadline = System.currentTimeMillis() + 30000;
        do {
            MvcResult r = mockMvc.perform(get("/api/apartment/sign-archive/tasks/" + taskNo)
                            .header("X-Auth-Token", stewardToken))
                    .andExpect(status().isOk()).andReturn();
            data = readNode(r).path("data");
            int progress = data.path("progress").asInt();
            assertEquals(0, progress % 5, "进度必须按 5% 一步: " + progress);
            assertTrue(progress >= last, "进度单调递增");
            last = progress;
            if ("SUCCESS".equals(data.path("status").asText())) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);

        assertEquals("SUCCESS", data.path("status").asText(), "任务应在超时前成功: " + data);
        assertEquals(100, data.path("progress").asInt());
        assertEquals(20, data.path("currentStep").asInt());
        assertEquals(false, data.path("cancelable").asBoolean());
        assertNotNull(data.path("result").path("platformFlowNo").asText(null));
        assertNotNull(data.path("result").path("signedFileName").asText(null));

        Lease lease = leaseMapper.selectOne(new QueryWrapper<Lease>().eq("contract_no", contractNo));
        assertNotNull(lease);
        assertEquals(Lease.STATUS_ACTIVE, lease.getStatus());
        assertNotNull(lease.getSignArchiveId(), "租约应回写归档 id");
        assertEquals("steward01", lease.getOperator());

        LeaseSignArchive archive = archiveMapper.selectById(lease.getSignArchiveId());
        assertEquals(LeaseSignArchive.SIGN_FINISHED, archive.getSignStatus());
        assertNotNull(archive.getFileContent());
        assertTrue(archive.getFileContent().contains("平台盖章:   已完成"), "文件应为平台出具的已签章凭证");
        assertNotNull(archive.getSignedAt());

        // 列表能查到且 SELF 口径含本人任务
        MvcResult list = mockMvc.perform(get("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        assertTrue(readNode(list).path("data").toString().contains(taskNo));
    }

    @Test
    void platformFailure_checkpointRetry_noDuplicateLeaseOrArchive() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-002", "示例小区");
        String contractNo = "HT-SIGN-FAIL-0001";
        String bizNo = SignArchiveStepHandler.toBizNo(contractNo);
        // 第 14 步「平台盖章」故障一次：任务应停在 65%（13 步已完成）的断点
        mockESign.injectFailure(bizNo, "seal", 1);

        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-SIGN-002", "");
        JsonNode failed = awaitStatus(stewardToken, taskNo, "FAILED", 30000);
        assertEquals(14, failed.path("failStep").asInt());
        assertEquals(65, failed.path("progress").asInt());
        assertTrue(failed.path("failReason").asText().contains("seal"));
        assertEquals(true, failed.path("retryable").asBoolean());

        // 断点前的成果已落库：租约 + 归档行（SIGNING/SIGNED）
        Long leaseCount = leaseMapper.selectCount(new QueryWrapper<Lease>().eq("contract_no", contractNo));
        assertEquals(1L, leaseCount);
        Long archiveCount = archiveMapper.selectCount(
                new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo));
        assertEquals(1L, archiveCount);
        String flowNoBefore = archiveMapper.selectOne(
                new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo)).getPlatformFlowNo();

        // 清故障后断点重跑：从第 14 步继续
        mockESign.clearFailure(bizNo);
        mockMvc.perform(post("/api/apartment/sign-archive/tasks/" + taskNo + "/retry")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.retryCount").value(1));
        JsonNode success = awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);
        assertEquals(100, success.path("progress").asInt());

        // 幂等：租约、归档、平台流水都只有一份
        assertEquals(1L, leaseMapper.selectCount(new QueryWrapper<Lease>().eq("contract_no", contractNo)));
        LeaseSignArchive archive = archiveMapper.selectOne(
                new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo));
        assertEquals(flowNoBefore, archive.getPlatformFlowNo(), "重跑沿用同一平台流水（业务幂等号）");
        assertEquals(LeaseSignArchive.SIGN_FINISHED, archive.getSignStatus());
    }

    @Test
    void cancelWhileWaitingSign_notRevivedBySignCallbackOrDispatch() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-010", "示例小区");
        String contractNo = "HT-SIGN-CANCEL-RACE-0001";
        String bizNo = SignArchiveStepHandler.toBizNo(contractNo);
        // 挂起自动签署：确定停在第 12 步等待（PENDING@55）
        mockESign.holdSign(bizNo);

        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-SIGN-010", "");
        // 等到确定停在等待态（PENDING 且 progress=55），而不是刚提交时的 PENDING@0
        long waitDeadline = System.currentTimeMillis() + 20000;
        JsonNode waiting = null;
        do {
            waiting = getTask(stewardToken, taskNo);
            if ("PENDING".equals(waiting.path("status").asText()) && waiting.path("progress").asInt() == 55) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < waitDeadline);
        assertEquals("PENDING", waiting.path("status").asText());
        assertEquals(55, waiting.path("progress").asInt());

        // 等待中取消：立即 CANCELLED@55
        mockMvc.perform(post("/api/apartment/sign-archive/tasks/" + taskNo + "/cancel")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.progress").value(55));

        // 租客随后补签：apt-9 的即时唤醒 SQL 以 PENDING+第11步 为条件，已取消必须命中 0 行
        String flowNo = archiveMapper.selectOne(
                new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo)).getPlatformFlowNo();
        mockESign.manualCompleteSign(flowNo);
        int woken = taskMapper.wakeWaitingForSign(contractNo, LocalDateTime.now());
        assertEquals(0, woken, "签署回调不得唤醒已取消任务");
        // 再跨过两个以上调度派发周期（dispatch 100ms）+ 一个签署轮询周期，确保没有任何在途执行
        Thread.sleep(800);

        JsonNode after = getTask(stewardToken, taskNo);
        assertEquals("CANCELLED", after.path("status").asText(), "回调/调度不得复活已取消任务: " + after);
        assertEquals(55, after.path("progress").asInt(), "进度只增不减，停在 55%");
        assertEquals(11, after.path("currentStep").asInt());

        // 关键副作用未继续：归档停在 SIGNED 之前、租约未回写归档 id（13-20 步一律没跑）
        LeaseSignArchive archive = archiveMapper.selectOne(
                new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo));
        assertEquals(LeaseSignArchive.SIGN_SIGNING, archive.getSignStatus(),
                "取消后不得继续盖章/归档");
        Lease lease = leaseMapper.selectOne(new QueryWrapper<Lease>().eq("contract_no", contractNo));
        assertNotNull(lease);
        assertEquals(null, lease.getSignArchiveId(), "取消后不得把归档回写租约（步骤 15 未执行）");
    }

    @Test
    void cancelWhileWaitingSign_stopsAt55Percent_thenRetryToSuccess() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-003", "示例小区");
        String contractNo = "HT-SIGN-CANCEL-0001";
        String bizNo = SignArchiveStepHandler.toBizNo(contractNo);
        // 挂起自动签署：任务确定停在第 12 步等待（PENDING + next_run_at），前 11 步 = 55%
        mockESign.holdSign(bizNo);

        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-SIGN-003", "");
        // 等到任务停在等待态（连续轮询观察到 PENDING 且 progress=55）
        long deadline = System.currentTimeMillis() + 20000;
        JsonNode data = null;
        do {
            data = getTask(stewardToken, taskNo);
            if (data.path("status").asText().equals("PENDING") && data.path("progress").asInt() == 55) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        assertEquals("PENDING", data.path("status").asText());
        assertEquals(55, data.path("progress").asInt(), "已完成 11 个一致步骤");

        // 等待中取消：立即落 CANCELLED，进度停在 55%（一致步骤边界）
        mockMvc.perform(post("/api/apartment/sign-archive/tasks/" + taskNo + "/cancel")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.progress").value(55))
                .andExpect(jsonPath("$.data.cancelable").value(false));

        // 租客补签 + 断点重跑：从第 12 步继续直到成功
        String flowNo = archiveMapper.selectOne(
                new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo)).getPlatformFlowNo();
        mockESign.manualCompleteSign(flowNo);
        mockMvc.perform(post("/api/apartment/sign-archive/tasks/" + taskNo + "/retry")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk());
        JsonNode success = awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);
        assertEquals(100, success.path("progress").asInt());
    }

    @Test
    void cancelFlagSetWhileQueued_executorStopsAtStepBoundaryZero_thenRetryCompletes() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-009", "示例小区");
        String contractNo = "HT-SIGN-RUNCANCEL-0001";
        // 关闭调度，由测试直接驱动执行器，确定性地验证“执行前已置取消标记→停在第 0 步边界”
        taskProperties.setEnabled(false);
        try {
            String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-SIGN-009", "");
            AsyncTask queued = taskMapper.selectOne(new QueryWrapper<AsyncTask>().eq("task_no", taskNo));
            assertEquals(AsyncTask.STATUS_PENDING, queued.getStatus());

            // 模拟取消标记在任务被领取前已落库（排队中取消 / 上一瞬请求取消）
            AsyncTask flag = new AsyncTask();
            flag.setId(queued.getId());
            flag.setCancelRequested(true);
            taskMapper.updateById(flag);

            taskExecutor.claimAndRun(queued.getId());
            JsonNode cancelled = getTask(stewardToken, taskNo);
            assertEquals("CANCELLED", cancelled.path("status").asText());
            assertEquals(0, cancelled.path("progress").asInt(), "一步未执行，停在 0% 一致边界");
            assertEquals(0, cancelled.path("currentStep").asInt());
            // 取消早于任何副作用：无租约、无归档
            assertEquals(0L, leaseMapper.selectCount(new QueryWrapper<Lease>().eq("contract_no", contractNo)));
            assertEquals(0L, archiveMapper.selectCount(
                    new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo)));

            // 解除取消并断点重跑（此任务 0 步已完成，等价从头跑），手动驱动直到成功
            mockMvc.perform(post("/api/apartment/sign-archive/tasks/" + taskNo + "/retry")
                    .header("X-Auth-Token", stewardToken)).andExpect(status().isOk());
            long deadline = System.currentTimeMillis() + 30000;
            JsonNode data;
            do {
                AsyncTask pending = taskMapper.selectOne(new QueryWrapper<AsyncTask>().eq("task_no", taskNo));
                if (AsyncTask.STATUS_PENDING.equals(pending.getStatus())) {
                    taskExecutor.claimAndRun(pending.getId());
                }
                data = getTask(stewardToken, taskNo);
                if ("SUCCESS".equals(data.path("status").asText()) || "FAILED".equals(data.path("status").asText())) {
                    break;
                }
                Thread.sleep(100);
            } while (System.currentTimeMillis() < deadline);
            assertEquals("SUCCESS", data.path("status").asText(), "重跑应成功: " + data);
        } finally {
            taskProperties.setEnabled(true);
        }
    }

    @Test
    void sensitiveWord_blockRejectsSubmit_maskStoredInPayload() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-004", "示例小区");

        // 拦截级：发起失败且不产生任务
        mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken)
                        .contentType("application/json")
                        .content(submitJson("HT-SIGN-BLOCK-0001", "PROP-SIGN-004", "内含赌博内容")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        Long blocked = taskMapper.selectCount(new QueryWrapper<AsyncTask>()
                .eq("biz_ref", "HT-SIGN-BLOCK-0001"));
        assertEquals(0L, blocked);

        // 打码级：发起成功，payload 内 remark 已打码
        String taskNo = submitAndGetTaskNo(stewardToken, "HT-SIGN-MASK-0001", "PROP-SIGN-004", "请加微信联系");
        AsyncTask task = taskMapper.selectOne(new QueryWrapper<AsyncTask>().eq("task_no", taskNo));
        assertTrue(task.getPayload().contains("***") || task.getPayload().contains("*"),
                "打码后的 remark 应进入 payload");
        assertFalse(task.getPayload().contains("加微信"), "payload 不应保留命中的明文词条");
        // 等到终态，避免后台任务跨测试被 mock 重置打断
        awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);
    }

    @Test
    void dataScope_selfOnlyAndScopeCommunity() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-005", "示例小区");
        registerLedger(supervisorToken, "PROP-SIGN-OTHER", "别的小区");

        // 挂起签署，让本测试发起的任务停在等待态，结尾可控取消（不跨测试残留）
        mockESign.holdSign(SignArchiveStepHandler.toBizNo("HT-SIGN-SCOPE-0001"));
        // area01 片区范围=示例小区：示例小区可发起
        String taskInScope = submitAndGetTaskNo(areaToken, "HT-SIGN-SCOPE-0001", "PROP-SIGN-005", "");
        assertNotNull(taskInScope);
        // 越区发起 403
        mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", areaToken)
                        .contentType("application/json")
                        .content(submitJson("HT-SIGN-SCOPE-DENY", "PROP-SIGN-OTHER", "")))
                .andExpect(status().isForbidden());

        // steward01（SELF）看不到 area01 发起的任务
        MvcResult list = mockMvc.perform(get("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk()).andReturn();
        assertFalse(readNode(list).path("data").toString().contains(taskInScope),
                "SELF 角色列表不应含他人任务");
        // area01（SCOPE=示例小区）能看到自己的
        MvcResult areaList = mockMvc.perform(get("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", areaToken))
                .andExpect(status().isOk()).andReturn();
        assertTrue(readNode(areaList).path("data").toString().contains(taskInScope));

        // 非发起人不能取消别人的任务
        mockMvc.perform(post("/api/apartment/sign-archive/tasks/" + taskInScope + "/cancel")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isForbidden());

        // 发起人本人取消，任务在测试内达到终态（不残留到下个测试）
        mockMvc.perform(post("/api/apartment/sign-archive/tasks/" + taskInScope + "/cancel")
                        .header("X-Auth-Token", areaToken))
                .andExpect(status().isOk());
        awaitStatus(areaToken, taskInScope, "CANCELLED", 20000);
    }

    @Test
    void duplicateSubmitRejected_andAnonymousUnauthorized() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-006", "示例小区");
        String taskNo = submitAndGetTaskNo(stewardToken, "HT-SIGN-DUP-0001", "PROP-SIGN-006", "");
        assertNotNull(taskNo);
        // 进行中重复发起
        mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken)
                        .contentType("application/json")
                        .content(submitJson("HT-SIGN-DUP-0001", "PROP-SIGN-006", "")))
                .andExpect(jsonPath("$.success").value(false));
        // 未登录 401
        mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .contentType("application/json")
                        .content(submitJson("HT-SIGN-ANON-0001", "PROP-SIGN-006", "")))
                .andExpect(status().isUnauthorized());

        // 成功后再次发起同样被拒（合同已签约）
        awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);
        mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", stewardToken)
                        .contentType("application/json")
                        .content(submitJson("HT-SIGN-DUP-0001", "PROP-SIGN-006", "")))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void orphanedRunningRecoveredThenRunsToSuccess() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-007", "示例小区");
        String contractNo = "HT-SIGN-ORPHAN-0001";
        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-SIGN-007", "");
        // 模拟进程宕机：任务被领走但未完成
        AsyncTask task = taskMapper.selectOne(new QueryWrapper<AsyncTask>().eq("task_no", taskNo));
        AsyncTask running = new AsyncTask();
        running.setId(task.getId());
        running.setStatus(AsyncTask.STATUS_RUNNING);
        running.setLockedBy("dead-node");
        running.setLockAt(LocalDateTime.now().minusMinutes(5));
        asyncTaskMapper.updateById(running);
        // 等它先别被调度以 RUNNING 状态推进：直接触发回收（调度只认领 PENDING，RUNNING 不会被领）
        assertEquals(1, taskExecutor.recoverOrphaned());
        JsonNode success = awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);
        assertEquals(100, success.path("progress").asInt());
    }

    @Test
    void retentionCleanupDeletesOnlyExpiredTerminalTasks() throws Exception {
        registerLedger(supervisorToken, "PROP-SIGN-008", "示例小区");
        String contractNo = "HT-SIGN-RETAIN-0001";
        String taskNo = submitAndGetTaskNo(stewardToken, contractNo, "PROP-SIGN-008", "");
        awaitStatus(stewardToken, taskNo, "SUCCESS", 30000);

        // 手工塞一条已过期终态任务（retain_until 在昨天）
        AsyncTask expired = new AsyncTask();
        expired.setTaskNo("TASK-EXPIRED-1");
        expired.setTaskType(AsyncTask.TYPE_SIGN_ARCHIVE);
        expired.setBizRef("HT-EXPIRED-1");
        expired.setPropertyCode("PROP-SIGN-008");
        expired.setStatus(AsyncTask.STATUS_SUCCESS);
        expired.setProgress(100);
        expired.setCurrentStep(20);
        expired.setTotalSteps(20);
        expired.setCancelRequested(false);
        expired.setRetryCount(0);
        expired.setPayload("{\"contractNo\":\"HT-EXPIRED-1\"}");
        expired.setRequestNo("REQ-EXPIRED-1");
        expired.setOperator("steward01");
        expired.setFinishedAt(LocalDateTime.now().minusDays(15));
        expired.setRetainUntil(LocalDateTime.now().minusDays(1));
        expired.setCreatedAt(LocalDateTime.now().minusDays(15));
        expired.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(expired);

        int deleted = taskExecutor.deleteExpired();
        assertTrue(deleted >= 1);
        assertEquals(0L, taskMapper.selectCount(new QueryWrapper<AsyncTask>().eq("task_no", "TASK-EXPIRED-1")));
        // 刚完成的任务保留期未到，仍在
        assertEquals(1L, taskMapper.selectCount(new QueryWrapper<AsyncTask>().eq("task_no", taskNo)));
    }

    // ---------------- helpers ----------------

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return readNode(result).path("data").path("token").asText();
    }

    private void registerLedger(String token, String propertyCode, String community) throws Exception {
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("{\"propertyCode\":\"" + propertyCode + "\",\"community\":\"" + community
                                + "\",\"building\":\"1栋\",\"roomNo\":\"101\",\"area\":50.5,"
                                + "\"requestNo\":\"REQ-" + propertyCode + "\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private String submitJson(String contractNo, String propertyCode, String remark) {
        String remarkPart = remark.isEmpty() ? "" : ",\"remark\":\"" + remark + "\"";
        return "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"" + propertyCode + "\","
                + "\"tenantName\":\"张三\",\"tenantPhone\":\"13800000000\",\"tenantIdCard\":\"110101199001011234\","
                + "\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                + "\"monthlyRent\":3000,\"deposit\":1000" + remarkPart
                + ",\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"ignored\"}";
    }

    private String submitAndGetTaskNo(String token, String contractNo, String propertyCode, String remark)
            throws Exception {
        MvcResult r = mockMvc.perform(post("/api/apartment/sign-archive/tasks")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content(submitJson(contractNo, propertyCode, remark)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readNode(r).path("data").path("taskNo").asText();
    }

    private JsonNode getTask(String token, String taskNo) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/apartment/sign-archive/tasks/" + taskNo)
                        .header("X-Auth-Token", token))
                .andExpect(status().isOk()).andReturn();
        return readNode(r).path("data");
    }

    private JsonNode awaitStatus(String token, String taskNo, String status, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode data;
        do {
            data = getTask(token, taskNo);
            if (status.equals(data.path("status").asText())) {
                return data;
            }
            // 等 FAILED 时若提前进入其他终态要立即失败暴露问题
            String s = data.path("status").asText();
            if (!"PENDING".equals(s) && !"RUNNING".equals(s) && !status.equals(s)) {
                // 允许目标状态之外继续等（如 CANCELLED 场景），但若等待 SUCCESS 却 FAILED 则快速失败
                if ("SUCCESS".equals(status) && ("FAILED".equals(s) || "CANCELLED".equals(s))) {
                    throw new AssertionError("等待 " + status + " 但任务进入 " + s + ": " + data);
                }
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("任务 " + taskNo + " 在 " + timeoutMs + "ms 内未达到 " + status + ": " + data);
    }

    private JsonNode readNode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
