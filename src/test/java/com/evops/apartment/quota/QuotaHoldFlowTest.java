package com.evops.apartment.quota;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.quota.dto.QuotaAdjustRequest;
import com.evops.apartment.quota.dto.QuotaApplyRequest;
import com.evops.apartment.quota.dto.QuotaCancelRequest;
import com.evops.apartment.quota.dto.QuotaConfirmRequest;
import com.evops.apartment.quota.entity.QuotaBalance;
import com.evops.apartment.quota.entity.QuotaHold;
import com.evops.apartment.quota.entity.QuotaJournal;
import com.evops.apartment.quota.mapper.QuotaBalanceMapper;
import com.evops.apartment.quota.mapper.QuotaHoldMapper;
import com.evops.apartment.quota.mapper.QuotaJournalMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-7 出租率额度预占模型：管家提的边界场景逐条验证。
 *
 * 核心并发/状态机/留痕走服务层（无登录上下文，不施加数据范围），
 * 鉴权/数据范围/参数校验走 MockMvc 真实 HTTP 链路。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_apt7_test;DB_CLOSE_DELAY=-1",
        "evops.quota.sweep-interval-ms=600000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotaHoldFlowTest {

    @Autowired private QuotaService quotaService;
    @Autowired private QuotaBalanceMapper balanceMapper;
    @Autowired private QuotaHoldMapper holdMapper;
    @Autowired private QuotaJournalMapper journalMapper;
    @Autowired private QuotaHoldTimeoutScheduler scheduler;
    @Autowired private MockMvc mockMvc;

    // ------------------------------------------------------------------
    // 一、申请先占余量
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void apply_consumesOneAvailable_andWritesReserveJournal() {
        openQuota("申请小区", 10);
        QuotaHold hold = apply("申请小区", "HT-APPLY-1");

        assertEquals(QuotaHold.STATUS_HELD, hold.getStatus());
        assertNotNull(hold.getHoldNo());
        assertNotNull(hold.getExpiresAt());
        QuotaBalance b = quotaService.getBalance("申请小区");
        assertEquals(10, b.getTotalAmount());
        assertEquals(0, b.getOccupiedAmount());
        assertEquals(1, b.getReservedAmount());
        assertEquals(9, b.available());
        // 账实相符：账上预占额 = HELD 预占单之和
        assertEquals(1, balanceMapper.sumHeldReserved("申请小区"));

        QuotaJournal j = latestJournal("申请小区");
        assertEquals(QuotaJournal.TYPE_RESERVE, j.getChangeType());
        assertEquals(0, j.getDeltaTotal());
        assertEquals(0, j.getDeltaOccupied());
        assertEquals(1, j.getDeltaReserved());
        assertEquals(1, j.getReservedAfter());
        assertEquals(hold.getId(), j.getHoldId());
    }

    @Test
    @Order(2)
    void apply_unknownQuotaAndMissingParams_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> apply("未开户小区", "HT-X-1"),
                "未配置配额的小区必须拒绝");
    }

    // ------------------------------------------------------------------
    // 二、并发不超扣（出租率检查的核心卡点）
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void concurrentApply_neverOversellsExactlyTotalSucceed() throws Exception {
        openQuota("抢额小区", 3);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                ready.countDown();
                try {
                    fire.await(5, TimeUnit.SECONDS);
                    apply("抢额小区", "HT-RACE-" + idx);
                    success.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    rejected.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        fire.countDown();
        TimeUnit.SECONDS.sleep(3);

        assertTrue(unexpected.isEmpty(), "出现非预期异常: " + unexpected);
        assertEquals(3, success.get(), "配额 3，只能有 3 笔预占成功");
        assertEquals(5, rejected.get(), "其余 5 笔必须被告知余量不足");
        QuotaBalance b = quotaService.getBalance("抢额小区");
        assertEquals(3, b.getReservedAmount());
        assertEquals(0, b.available(), "占满后余量必须为 0，绝不超扣");
        assertEquals(3, balanceMapper.sumHeldReserved("抢额小区"));
        // 占满后再来一笔仍被拒绝
        assertThrows(IllegalArgumentException.class, () -> apply("抢额小区", "HT-RACE-AFTER"));
    }

    @Test
    @Order(4)
    void apply_whenExactlyOneLeft_lastOneSucceedsNextRejected() {
        openQuota("临界小区", 1);
        apply("临界小区", "HT-EDGE-1");
        QuotaBalance b = quotaService.getBalance("临界小区");
        assertEquals(0, b.available(), "唯一余量被占，余 0");
        assertThrows(IllegalArgumentException.class, () -> apply("临界小区", "HT-EDGE-2"),
                "恰好占满时新申请必须拒绝");
    }

    // ------------------------------------------------------------------
    // 三、确认才实扣
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    void confirm_convertsReservedToOccupied_andJournalBalances() {
        openQuota("确认小区", 5);
        QuotaHold hold = apply("确认小区", "HT-CONFIRM-1");

        QuotaConfirmRequest req = new QuotaConfirmRequest();
        req.setHoldNo(hold.getHoldNo());
        req.setRequestNo("REQ-CONFIRM-1");
        req.setOperator("op-test");
        QuotaHold confirmed = quotaService.confirm(req);

        assertEquals(QuotaHold.STATUS_CONFIRMED, confirmed.getStatus());
        assertNotNull(confirmed.getConfirmedAt());
        QuotaBalance b = quotaService.getBalance("确认小区");
        assertEquals(1, b.getOccupiedAmount());
        assertEquals(0, b.getReservedAmount());
        assertEquals(4, b.available());
        assertEquals(0, balanceMapper.sumHeldReserved("确认小区"), "HELD 之和应随确认归零");

        List<QuotaJournal> js = journals("确认小区", null);
        // 开户(ADJUST) + 预占(RESERVE) + 确认(CONFIRM) 共 3 条
        assertEquals(3, js.size());
        QuotaJournal confirmLog = js.get(2);
        assertEquals(QuotaJournal.TYPE_CONFIRM, confirmLog.getChangeType());
        assertEquals(1, confirmLog.getDeltaOccupied());
        assertEquals(-1, confirmLog.getDeltaReserved());
        assertEquals(1, confirmLog.getOccupiedAfter());
        assertEquals(0, confirmLog.getReservedAfter());
    }

    @Test
    @Order(6)
    void confirm_twice_rejected() {
        openQuota("重复确认小区", 5);
        QuotaHold hold = apply("重复确认小区", "HT-DC-1");
        confirm(hold.getHoldNo(), "REQ-DC-1");
        assertThrows(IllegalArgumentException.class, () -> confirm(hold.getHoldNo(), "REQ-DC-2"),
                "已确认的预占单不能重复确认");
        QuotaBalance b = quotaService.getBalance("重复确认小区");
        assertEquals(1, b.getOccupiedAmount(), "重复确认不得二次实扣");
    }

    // ------------------------------------------------------------------
    // 四、取消释放
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    void cancel_releasesReservation_onlyOnce() {
        openQuota("取消小区", 4);
        QuotaHold hold = apply("取消小区", "HT-CANCEL-1");
        assertEquals(3, quotaService.getBalance("取消小区").available());

        QuotaCancelRequest req = new QuotaCancelRequest();
        req.setHoldNo(hold.getHoldNo());
        req.setRequestNo("REQ-CANCEL-1");
        req.setOperator("op-test");
        QuotaHold cancelled = quotaService.cancel(req);
        assertEquals(QuotaHold.STATUS_RELEASED, cancelled.getStatus());
        assertEquals(QuotaHold.REASON_CANCEL, cancelled.getReleaseReason());
        assertEquals(4, quotaService.getBalance("取消小区").available(), "取消后余量回补");
        assertEquals(0, balanceMapper.sumHeldReserved("取消小区"));

        QuotaJournal j = latestJournal("取消小区");
        assertEquals(QuotaJournal.TYPE_RELEASE_CANCEL, j.getChangeType());
        assertEquals(-1, j.getDeltaReserved());
        assertEquals(0, j.getReservedAfter());

        // 重复取消拒绝，账不再动
        assertThrows(IllegalArgumentException.class, () -> quotaService.cancel(req), "重复取消必须拒绝");
        // 已取消的不能再确认
        assertThrows(IllegalArgumentException.class, () -> confirm(hold.getHoldNo(), "REQ-CANCEL-X"));
        // 释放后同单可重新申请（新单号）
        QuotaHold again = apply("取消小区", "HT-CANCEL-1");
        assertNotNull(again.getId());
        assertFalse(again.getHoldNo().equals(hold.getHoldNo()), "重新申请应为新预占单号");
        long rowsForBiz = holdMapper.selectCount(new QueryWrapper<QuotaHold>().eq("biz_ref", "HT-CANCEL-1"));
        assertEquals(2L, rowsForBiz, "旧 RELEASED 单保留留痕 + 新活跃单");
    }

    @Test
    @Order(8)
    void cancel_afterConfirm_rejected() {
        openQuota("确认后取消小区", 4);
        QuotaHold hold = apply("确认后取消小区", "HT-CC-1");
        confirm(hold.getHoldNo(), "REQ-CC-1");
        assertThrows(IllegalArgumentException.class,
                () -> cancel(hold.getHoldNo(), "REQ-CC-2"),
                "已实扣不能取消，额度需走退租释放");
        assertEquals(1, quotaService.getBalance("确认后取消小区").getOccupiedAmount());
    }

    // ------------------------------------------------------------------
    // 五、20 分钟超时自动释放
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    void expiredHold_cannotConfirm_andSchedulerAutoReleases() {
        openQuota("超时小区", 4);
        QuotaHold hold = apply("超时小区", "HT-TIMEOUT-1");
        // 把到期时间拨到过去，模拟申请后超过 20 分钟仍未确认
        hold.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        holdMapper.updateById(hold);

        assertThrows(IllegalArgumentException.class,
                () -> confirm(hold.getHoldNo(), "REQ-TO-1"),
                "过期预占必须拒绝确认");
        assertEquals(1, quotaService.getBalance("超时小区").getReservedAmount(), "拒绝确认不得动账");

        // 后台扫描自动释放
        scheduler.sweepExpired();
        QuotaHold reloaded = holdMapper.selectById(hold.getId());
        assertEquals(QuotaHold.STATUS_RELEASED, reloaded.getStatus());
        assertEquals(QuotaHold.REASON_TIMEOUT, reloaded.getReleaseReason());
        assertEquals(4, quotaService.getBalance("超时小区").available(), "超时释放余量回补");
        assertEquals(0, balanceMapper.sumHeldReserved("超时小区"));
        QuotaJournal j = latestJournal("超时小区");
        assertEquals(QuotaJournal.TYPE_RELEASE_TIMEOUT, j.getChangeType());

        // 幂等：再扫一轮/再手工释放一次均空转，不重复回补
        scheduler.sweepExpired();
        assertFalse(quotaService.releaseIfExpired(hold.getId()));
        assertEquals(4, quotaService.getBalance("超时小区").available());
    }

    @Test
    @Order(10)
    void cancelRacingWithTimeout_onlyOneReleasesAndOneJournal() {
        openQuota("竞争小区", 2);
        QuotaHold hold = apply("竞争小区", "HT-RACE-CANCEL-1");
        hold.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        holdMapper.updateById(hold);

        // 超时扫描方先赢得 CAS（与生产里调度线程先动手等价）
        assertTrue(quotaService.releaseIfExpired(hold.getId()));
        // 此时管家点取消：必须得知已被超时释放，且账只回补一次
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cancel(hold.getHoldNo(), "REQ-RACE-1"));
        assertTrue(ex.getMessage().contains("超时"), "取消方应被告知已超时释放: " + ex.getMessage());

        QuotaBalance b = quotaService.getBalance("竞争小区");
        assertEquals(0, b.getReservedAmount());
        assertEquals(2, b.available());
        long cancelLogs = journalMapper.selectCount(new QueryWrapper<QuotaJournal>()
                .eq("community", "竞争小区").eq("change_type", QuotaJournal.TYPE_RELEASE_CANCEL));
        long timeoutLogs = journalMapper.selectCount(new QueryWrapper<QuotaJournal>()
                .eq("community", "竞争小区").eq("change_type", QuotaJournal.TYPE_RELEASE_TIMEOUT));
        assertEquals(0L, cancelLogs, "输掉竞争的取消不得写流水");
        assertEquals(1L, timeoutLogs, "超时释放只能有一笔流水");
    }

    @Test
    @Order(11)
    void confirmRacingWithTimeout_businessFailureNotSystemError() {
        openQuota("确认竞争小区", 2);
        QuotaHold hold = apply("确认竞争小区", "HT-RACE-CONFIRM-1");
        hold.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        holdMapper.updateById(hold);
        // 超时方先释放；此后管家点确认（过期前置检查与 CAS 都可能落空）必须是业务失败，账不动
        assertTrue(quotaService.releaseIfExpired(hold.getId()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> confirm(hold.getHoldNo(), "REQ-RACE-C-1"));
        assertTrue(ex.getMessage().contains("超时"), "应明确告知超时释放: " + ex.getMessage());
        QuotaBalance b = quotaService.getBalance("确认竞争小区");
        assertEquals(0, b.getOccupiedAmount(), "输掉竞争的确认不得实扣");
        assertEquals(0, b.getReservedAmount());
        assertEquals(2, b.available());
        long confirms = journalMapper.selectCount(new QueryWrapper<QuotaJournal>()
                .eq("community", "确认竞争小区").eq("change_type", QuotaJournal.TYPE_CONFIRM));
        assertEquals(0L, confirms, "输掉竞争不得写 CONFIRM 流水");
    }

    // ------------------------------------------------------------------
    // 六、同单不可重复预占（含并发）
    // ------------------------------------------------------------------

    @Test
    @Order(12)
    void sameBizRef_duplicateApplyRejected_evenConcurrently() throws Exception {
        openQuota("同单小区", 5);
        apply("同单小区", "HT-DUP-1");
        // 已有 HELD 单时，友好前置拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> apply("同单小区", "HT-DUP-1"));
        assertTrue(ex.getMessage().contains("同单不可重复预占"));

        // 并发同单：唯一索引兜底，只成功一笔（前置校验可能双双通过，落库时一胜一败）
        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    fire.await(5, TimeUnit.SECONDS);
                    apply("同单小区", "HT-DUP-RACE");
                    success.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    rejected.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        fire.countDown();
        TimeUnit.SECONDS.sleep(2);
        assertTrue(unexpected.isEmpty(), "非预期异常: " + unexpected);
        assertEquals(1, success.get(), "并发同单只可成功一笔");
        assertEquals(threads - 1, rejected.get());
        // 一笔 HT-DUP-1 + 一笔 HT-DUP-RACE = 预占 2
        assertEquals(2, quotaService.getBalance("同单小区").getReservedAmount());
    }

    // ------------------------------------------------------------------
    // 七、配额调整留痕 + 下调保护
    // ------------------------------------------------------------------

    @Test
    @Order(13)
    void quotaAdjust_opensUpsAndDowns_withJournal_andFloorGuard() {
        // 开户
        QuotaBalance opened = openQuota("配额小区", 10);
        assertEquals(10, opened.getTotalAmount());
        QuotaHold hold = apply("配额小区", "HT-ADJ-1");
        confirm(hold.getHoldNo(), "REQ-ADJ-1");              // occupied=1
        QuotaHold held = apply("配额小区", "HT-ADJ-2");      // reserved=1

        // 上调
        adjust("配额小区", 20, "REQ-ADJ-UP");
        QuotaBalance b = quotaService.getBalance("配额小区");
        assertEquals(20, b.getTotalAmount());
        assertEquals(18, b.available());

        // 下调到恰好 = occupied + reserved = 2，允许
        adjust("配额小区", 2, "REQ-ADJ-DOWN-OK");
        assertEquals(2, quotaService.getBalance("配额小区").getTotalAmount());

        // 下调击穿在途单（< 2）必须拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adjust("配额小区", 1, "REQ-ADJ-DOWN-BAD"));
        assertTrue(ex.getMessage().contains("已实扣 + 预占中"));
        assertEquals(2, quotaService.getBalance("配额小区").getTotalAmount(), "拒绝后配额不变");

        // 取消在途单后下调到 1（只剩 occupied=1）允许
        cancel(held.getHoldNo(), "REQ-ADJ-CANCEL");
        adjust("配额小区", 1, "REQ-ADJ-DOWN-1");
        assertEquals(1, quotaService.getBalance("配额小区").getTotalAmount());
        assertEquals(0, quotaService.getBalance("配额小区").available());

        // 全程留痕：开户/上调/下调2/拒绝(无)/下调1，共 4 条 ADJUST，delta 与 after 可回放
        List<QuotaJournal> adjusts = journals("配额小区", QuotaJournal.TYPE_ADJUST);
        assertEquals(4, adjusts.size());
        assertEquals(10, adjusts.get(0).getDeltaTotal());
        assertEquals(10, adjusts.get(0).getTotalAfter());
        assertEquals(10, adjusts.get(1).getDeltaTotal());
        assertEquals(20, adjusts.get(1).getTotalAfter());
        assertEquals(-18, adjusts.get(2).getDeltaTotal());
        assertEquals(2, adjusts.get(2).getTotalAfter());
        assertEquals(-1, adjusts.get(3).getDeltaTotal());
        assertEquals(1, adjusts.get(3).getTotalAfter());
    }

    // ------------------------------------------------------------------
    // 八、流水回放对账：余量变化全程可查
    // ------------------------------------------------------------------

    @Test
    @Order(14)
    void journal_replaysToCurrentBalance_andReservedMatchesHeldRows() {
        openQuota("对账小区", 6);
        QuotaHold h1 = apply("对账小区", "HT-REC-1");
        QuotaHold h2 = apply("对账小区", "HT-REC-2");
        confirm(h1.getHoldNo(), "REQ-REC-C1");
        cancel(h2.getHoldNo(), "REQ-REC-X2");
        QuotaHold h3 = apply("对账小区", "HT-REC-3");
        h3.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        holdMapper.updateById(h3);
        quotaService.releaseIfExpired(h3.getId());
        adjust("对账小区", 8, "REQ-REC-ADJ");

        // 用流水从 0 回放，终态必须等于账上现值
        int total = 0, occupied = 0, reserved = 0;
        for (QuotaJournal j : journals("对账小区", null)) {
            total += j.getDeltaTotal();
            occupied += j.getDeltaOccupied();
            reserved += j.getDeltaReserved();
            // 每条流水记录的 after 快照必须与逐步累加之和一致
            assertEquals(total, j.getTotalAfter(), "total 快照不一致于流水 #" + j.getId());
            assertEquals(occupied, j.getOccupiedAfter(), "occupied 快照不一致于流水 #" + j.getId());
            assertEquals(reserved, j.getReservedAfter(), "reserved 快照不一致于流水 #" + j.getId());
        }
        QuotaBalance b = quotaService.getBalance("对账小区");
        assertEquals(total, b.getTotalAmount());
        assertEquals(occupied, b.getOccupiedAmount());
        assertEquals(reserved, b.getReservedAmount());
        assertEquals(1, b.getOccupiedAmount());
        assertEquals(0, b.getReservedAmount());
        assertEquals(7, b.available());
        assertEquals(reserved, balanceMapper.sumHeldReserved("对账小区"), "账实相符");

        // 按业务单过滤：HT-REC-2 应只见 RESERVE + RELEASE_CANCEL 两条
        List<QuotaJournal> forBiz = quotaService.searchJournal("对账小区", null, "HT-REC-2");
        assertEquals(2, forBiz.size());
        assertEquals(QuotaJournal.TYPE_RESERVE, forBiz.get(0).getChangeType());
        assertEquals(QuotaJournal.TYPE_RELEASE_CANCEL, forBiz.get(1).getChangeType());
    }

    // ------------------------------------------------------------------
    // 九、HTTP 链路：鉴权、数据范围、参数校验
    // ------------------------------------------------------------------

    @Test
    @Order(15)
    void http_auth_scopeAndValidation() throws Exception {
        openQuota("HTTP小区", 3);

        // 未登录 401
        mockMvc.perform(post("/api/apartment/quota/holds")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        String steward = login("steward01", "steward123");
        String area = login("area01", "area123");
        String supervisor = login("supervisor01", "supervisor123");

        // 管家申请（SELF 不校验小区归属，操作人落登录账号）
        MvcResult r = mockMvc.perform(post("/api/apartment/quota/holds")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"community\":\"HTTP小区\",\"bizRef\":\"HT-HTTP-1\","
                                + "\"requestNo\":\"REQ-HTTP-1\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HELD"))
                .andExpect(jsonPath("$.data.operator").value("steward01"))
                .andReturn();
        String holdNo = readField(r.getResponse().getContentAsString(), "holdNo");

        // 余量查询：返回 available
        mockMvc.perform(get("/api/apartment/quota/balances?community=HTTP小区")
                        .header("X-Auth-Token", steward))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(3))
                .andExpect(jsonPath("$.data.reservedAmount").value(1))
                .andExpect(jsonPath("$.data.available").value(2));

        // 参数校验：缺 bizRef → 业务失败
        mockMvc.perform(post("/api/apartment/quota/holds")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"community\":\"HTTP小区\",\"requestNo\":\"X\",\"operator\":\"y\"}"))
                .andExpect(jsonPath("$.success").value(false));

        // 配额调整仅主管/管理员：管家 403
        mockMvc.perform(post("/api/apartment/quota/adjustments")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"community\":\"HTTP小区\",\"newTotalAmount\":99,"
                                + "\"requestNo\":\"REQ-AJ-X\",\"operator\":\"ignored\"}"))
                .andExpect(status().isForbidden());
        // 主管可调整
        mockMvc.perform(post("/api/apartment/quota/adjustments")
                        .header("X-Auth-Token", supervisor)
                        .contentType("application/json")
                        .content("{\"community\":\"HTTP小区\",\"newTotalAmount\":30,"
                                + "\"requestNo\":\"REQ-AJ-OK\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(30));

        // SCOPE：片区管家可在示例小区操作，跨到未授权小区 403
        mockMvc.perform(post("/api/apartment/quota/holds")
                        .header("X-Auth-Token", area)
                        .contentType("application/json")
                        .content("{\"community\":\"其他小区\",\"bizRef\":\"HT-SCOPE-1\","
                                + "\"requestNo\":\"REQ-SCOPE-1\",\"operator\":\"ignored\"}"))
                .andExpect(status().isForbidden());
        // 片区管家在示例小区可以申请（种子额度 300）
        mockMvc.perform(post("/api/apartment/quota/holds")
                        .header("X-Auth-Token", area)
                        .contentType("application/json")
                        .content("{\"community\":\"示例小区\",\"bizRef\":\"HT-SCOPE-2\","
                                + "\"requestNo\":\"REQ-SCOPE-2\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operator").value("area01"))
                .andReturn().getResponse().getContentAsString();
        String areaHoldNo = holdMapper.selectOne(new QueryWrapper<QuotaHold>()
                .eq("biz_ref", "HT-SCOPE-2")).getHoldNo();

        // SELF：管家不能确认/取消别人（area01）申请的预占单 → 403
        mockMvc.perform(post("/api/apartment/quota/holds/" + areaHoldNo + "/confirm")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"requestNo\":\"REQ-SX\",\"operator\":\"ignored\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/apartment/quota/holds/" + areaHoldNo + "/cancel")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"requestNo\":\"REQ-SX2\",\"operator\":\"ignored\"}"))
                .andExpect(status().isForbidden());
        // SELF 列表只看得到本人（HTTP小区那笔），看不到 area01 的
        mockMvc.perform(get("/api/apartment/quota/holds").header("X-Auth-Token", steward))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bizRef").value("HT-HTTP-1"))
                .andExpect(jsonPath("$..bizRef",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("HT-SCOPE-2"))));

        // 本人确认自己的单成功
        mockMvc.perform(post("/api/apartment/quota/holds/" + holdNo + "/confirm")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"requestNo\":\"REQ-HTTP-C\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // 流水必须指定小区
        mockMvc.perform(get("/api/apartment/quota/journals").header("X-Auth-Token", steward))
                .andExpect(jsonPath("$.success").value(false));
        // 管家可以看本小区流水（含主管的 ADJUST，留痕对经办角色可见）
        mockMvc.perform(get("/api/apartment/quota/journals?community=HTTP小区")
                        .header("X-Auth-Token", steward))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.changeType=='ADJUST')]").exists());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private QuotaBalance openQuota(String community, int total) {
        QuotaAdjustRequest req = new QuotaAdjustRequest();
        req.setCommunity(community);
        req.setNewTotalAmount(total);
        req.setRequestNo("REQ-OPEN-" + UUID.randomUUID().toString().substring(0, 8));
        req.setOperator("op-setup");
        return quotaService.adjust(req);
    }

    private QuotaHold apply(String community, String bizRef) {
        QuotaApplyRequest req = new QuotaApplyRequest();
        req.setCommunity(community);
        req.setBizRef(bizRef);
        req.setRequestNo("REQ-" + bizRef + "-" + UUID.randomUUID().toString().substring(0, 6));
        req.setOperator("op-test");
        return quotaService.apply(req);
    }

    private void confirm(String holdNo, String requestNo) {
        QuotaConfirmRequest req = new QuotaConfirmRequest();
        req.setHoldNo(holdNo);
        req.setRequestNo(requestNo);
        req.setOperator("op-test");
        quotaService.confirm(req);
    }

    private void cancel(String holdNo, String requestNo) {
        QuotaCancelRequest req = new QuotaCancelRequest();
        req.setHoldNo(holdNo);
        req.setRequestNo(requestNo);
        req.setOperator("op-test");
        quotaService.cancel(req);
    }

    private void adjust(String community, int newTotal, String requestNo) {
        QuotaAdjustRequest req = new QuotaAdjustRequest();
        req.setCommunity(community);
        req.setNewTotalAmount(newTotal);
        req.setRequestNo(requestNo);
        req.setOperator("op-supervisor");
        quotaService.adjust(req);
    }

    private QuotaJournal latestJournal(String community) {
        List<QuotaJournal> rows = journalMapper.selectList(new QueryWrapper<QuotaJournal>()
                .eq("community", community).orderByDesc("id").last("LIMIT 1"));
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private List<QuotaJournal> journals(String community, String changeType) {
        return journalMapper.selectList(new QueryWrapper<QuotaJournal>()
                .eq("community", community)
                .eq(changeType != null, "change_type", changeType)
                .orderByAsc("id"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"token\":\"");
        return body.substring(idx + 9, body.indexOf("\"", idx + 9));
    }

    private String readField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        return json.substring(idx + key.length(), json.indexOf("\"", idx + key.length()));
    }
}
