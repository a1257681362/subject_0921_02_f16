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
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.common.trace.TraceContext;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 出租率额度预占模型核心服务。
 *
 * 不变量（每一步都在同一事务内维护，异常整体回滚）：
 * 1. 不超扣：申请的“余量检查 + reserved+1”是一条带 WHERE available&gt;=1 的条件更新（行锁串行化），
 *    并发申请只可能有 total 笔成功；
 * 2. 账实相符：balance.reserved_amount 恒等于该小区 HELD 预占单 reserve_amount 之和
 *    （确认 reserved→occupied、取消/超时 reserved 回补，均成对发生）；
 * 3. 同单唯一：同一 biz_ref（合同号）同时只存在一笔活跃预占单，库内唯一索引兜底并发；
 * 4. 全程留痕：每一次变化落 t_quota_journal（含变化后三余额），配额调整同样落流水。
 */
@Service
public class QuotaService {
    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final QuotaBalanceMapper balanceMapper;
    private final QuotaHoldMapper holdMapper;
    private final QuotaJournalMapper journalMapper;
    private final ApartmentScopeGuard scopeGuard;
    private final QuotaProperties properties;

    public QuotaService(QuotaBalanceMapper balanceMapper,
                       QuotaHoldMapper holdMapper,
                       QuotaJournalMapper journalMapper,
                       ApartmentScopeGuard scopeGuard,
                       QuotaProperties properties) {
        this.balanceMapper = balanceMapper;
        this.holdMapper = holdMapper;
        this.journalMapper = journalMapper;
        this.scopeGuard = scopeGuard;
        this.properties = properties;
    }

    /** 申请预占：先占 1 个余量，20 分钟内确认才实扣。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaHold apply(QuotaApplyRequest req) {
        String previousContract = MDC.get(TraceContext.CONTRACT_NO);
        TraceContext.bindContract(req.getBizRef());
        try {
            scopeGuard.requireCommunityInScope(req.getCommunity());
            String operator = scopeGuard.resolveOperator(req.getOperator());
            LocalDateTime now = LocalDateTime.now();

            QuotaBalance balance = balanceMapper.selectOne(new QueryWrapper<QuotaBalance>()
                    .eq("community", req.getCommunity()));
            if (balance == null) {
                throw new IllegalArgumentException("小区尚未配置出租率配额: " + req.getCommunity());
            }
            // 友好前置校验（并发正确性仍由下面的条件更新与唯一索引兜底）
            QuotaHold active = findActiveByBizRef(req.getBizRef());
            if (active != null) {
                throw new IllegalArgumentException("业务单 " + req.getBizRef()
                        + " 已存在" + (QuotaHold.STATUS_HELD.equals(active.getStatus()) ? "预占中" : "已确认")
                        + "的预占单 " + active.getHoldNo() + "，同单不可重复预占");
            }
            if (balance.available() < QuotaBalance.RESERVE_AMOUNT) {
                throw new IllegalArgumentException("出租余量不足：配额 " + balance.getTotalAmount()
                        + "，已实扣 " + balance.getOccupiedAmount()
                        + "，预占中 " + balance.getReservedAmount());
            }

            // 原子预占：WHERE 余量 >= 1，并发下数据库行锁决定先后，不足者更新 0 行
            int reserved = balanceMapper.reserve(req.getCommunity(), QuotaBalance.RESERVE_AMOUNT,
                    req.getRequestNo(), operator, now);
            if (reserved == 0) {
                throw new IllegalArgumentException("出租余量不足，预占失败（可能并发申请已占满）");
            }

            QuotaHold hold = new QuotaHold();
            hold.setHoldNo("HOLD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
            hold.setCommunity(req.getCommunity());
            hold.setBizRef(req.getBizRef());
            hold.setReserveAmount(QuotaBalance.RESERVE_AMOUNT);
            hold.setStatus(QuotaHold.STATUS_HELD);
            hold.setExpiresAt(now.plusMinutes(properties.getHoldTtlMinutes()));
            hold.setRequestNo(req.getRequestNo());
            hold.setOperator(operator);
            hold.setBizTime(now);
            hold.setDataVersion(1);
            hold.setUpdatedAt(now);
            try {
                holdMapper.insert(hold);
            } catch (DuplicateKeyException e) {
                // 并发同单申请：唯一索引 uk_quota_hold_active_biz 兜底，本事务回滚，预占额不挂账
                throw new IllegalArgumentException(
                        "业务单 " + req.getBizRef() + " 已存在预占单，同单不可重复预占");
            }

            QuotaBalance after = reloadBalance(req.getCommunity());
            writeJournal(after, QuotaJournal.TYPE_RESERVE, hold,
                    0, 0, QuotaBalance.RESERVE_AMOUNT, req.getRequestNo(), operator, now);
            log.info("额度预占成功 holdNo={} community={} bizRef={} 余量={} requestNo={}",
                    hold.getHoldNo(), hold.getCommunity(), hold.getBizRef(), after.available(),
                    req.getRequestNo());
            scopeGuard.audit("apartment:quota:apply", req.getRequestNo(),
                    "community=" + req.getCommunity() + ",bizRef=" + req.getBizRef(), null);
            return hold;
        } finally {
            restoreContract(previousContract);
        }
    }

    /** 确认实扣：预占额转已实扣；过期/已释放/已确认均拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaHold confirm(QuotaConfirmRequest req) {
        if (!StringUtils.hasText(req.getHoldNo())) {
            throw new IllegalArgumentException("预占单号不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        QuotaHold hold = requireHold(req.getHoldNo());
        requireOperable(hold);
        if (!hold.held()) {
            throw new IllegalArgumentException(QuotaHold.STATUS_CONFIRMED.equals(hold.getStatus())
                    ? "预占单已确认，不能重复确认" : "预占单已释放（取消或超时），不能确认");
        }
        if (hold.expired(now)) {
            throw new IllegalArgumentException("预占已超过 " + properties.getHoldTtlMinutes()
                    + " 分钟有效期，系统将自动释放，请重新申请");
        }
        String operator = scopeGuard.resolveOperator(req.getOperator());

        // CAS 先锁状态流转：重复确认/并发取消或超时在此被挡住，只有一笔向下走账
        if (holdMapper.casConfirm(hold.getId(), now) == 0) {
            // 正常并发结果（过期检查后恰被超时释放/被他人确认）：给业务失败而非系统错误
            QuotaHold latest = holdMapper.selectById(hold.getId());
            String state = latest == null ? "已不存在"
                    : QuotaHold.STATUS_CONFIRMED.equals(latest.getStatus()) ? "已确认"
                    : QuotaHold.REASON_TIMEOUT.equals(latest.getReleaseReason()) ? "已超时自动释放"
                    : "已释放";
            throw new IllegalArgumentException("预占单" + state + "，确认未生效，请刷新后重试");
        }
        if (balanceMapper.confirm(hold.getCommunity(), hold.getReserveAmount(),
                req.getRequestNo(), operator, now) == 0) {
            throw new IllegalStateException("账上预占额不足，确认实扣失败（数据异常）");
        }
        QuotaBalance after = reloadBalance(hold.getCommunity());
        writeJournal(after, QuotaJournal.TYPE_CONFIRM, hold,
                0, hold.getReserveAmount(), -hold.getReserveAmount(),
                req.getRequestNo(), operator, now);
        log.info("额度确认实扣 holdNo={} community={} bizRef={} 已实扣={} requestNo={}",
                hold.getHoldNo(), hold.getCommunity(), hold.getBizRef(),
                after.getOccupiedAmount(), req.getRequestNo());
        scopeGuard.audit("apartment:quota:confirm", req.getRequestNo(),
                "holdNo=" + hold.getHoldNo() + ",bizRef=" + hold.getBizRef(), null);
        hold.setStatus(QuotaHold.STATUS_CONFIRMED);
        hold.setConfirmedAt(now);
        return hold;
    }

    /** 主动取消：释放预占额；与后台超时释放互斥（CAS 保证只释放、只回补一次）。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaHold cancel(QuotaCancelRequest req) {
        if (!StringUtils.hasText(req.getHoldNo())) {
            throw new IllegalArgumentException("预占单号不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        QuotaHold hold = requireHold(req.getHoldNo());
        requireOperable(hold);
        if (!hold.held()) {
            if (QuotaHold.STATUS_CONFIRMED.equals(hold.getStatus())) {
                throw new IllegalArgumentException("预占单已实扣，不能取消（额度需走退租流程释放）");
            }
            if (QuotaHold.REASON_TIMEOUT.equals(hold.getReleaseReason())) {
                throw new IllegalArgumentException("预占单已超过 " + properties.getHoldTtlMinutes()
                        + " 分钟未确认，已被超时自动释放，取消未生效");
            }
            throw new IllegalArgumentException("预占单已释放，无需重复取消");
        }
        String operator = scopeGuard.resolveOperator(req.getOperator());

        if (holdMapper.casRelease(hold.getId(), QuotaHold.REASON_CANCEL, now) == 0) {
            QuotaHold latest = holdMapper.selectById(hold.getId());
            throw new IllegalArgumentException("预占单已被"
                    + (latest != null && QuotaHold.REASON_TIMEOUT.equals(latest.getReleaseReason())
                            ? "超时自动释放" : "处理")
                    + "，取消未生效");
        }
        releaseReservation(hold, QuotaJournal.TYPE_RELEASE_CANCEL, QuotaHold.REASON_CANCEL,
                req.getRequestNo(), operator, now);
        log.info("额度预占取消并释放 holdNo={} community={} bizRef={} requestNo={}",
                hold.getHoldNo(), hold.getCommunity(), hold.getBizRef(), req.getRequestNo());
        scopeGuard.audit("apartment:quota:cancel", req.getRequestNo(),
                "holdNo=" + hold.getHoldNo() + ",bizRef=" + hold.getBizRef(), null);
        hold.setStatus(QuotaHold.STATUS_RELEASED);
        hold.setReleaseReason(QuotaHold.REASON_CANCEL);
        hold.setReleasedAt(now);
        return hold;
    }

    /**
     * 超时释放一笔预占单（后台扫描调用，无登录上下文）。
     * 独立事务：单笔失败不影响同轮其他单。返回是否由本次调用完成释放。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseIfExpired(Long holdId) {
        LocalDateTime now = LocalDateTime.now();
        QuotaHold hold = holdMapper.selectById(holdId);
        if (hold == null || !hold.held() || !hold.expired(now)) {
            return false;
        }
        // 与主动取消竞争：WHERE status='HELD'，输的一方 0 行、不动账、不写流水
        if (holdMapper.casRelease(holdId, QuotaHold.REASON_TIMEOUT, now) == 0) {
            return false;
        }
        releaseReservation(hold, QuotaJournal.TYPE_RELEASE_TIMEOUT, QuotaHold.REASON_TIMEOUT,
                null, "system", now);
        log.info("预占超 {} 分钟未确认，自动释放 holdNo={} community={} bizRef={}",
                properties.getHoldTtlMinutes(), hold.getHoldNo(), hold.getCommunity(), hold.getBizRef());
        return true;
    }

    /**
     * 配额调整（开户/上调/下调），仅主管/管理员。
     * 下调不得击穿在途单：新配额 &lt; 已实扣 + 预占中 拒绝；调整落 ADJUST 流水。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuotaBalance adjust(QuotaAdjustRequest req) {
        scopeGuard.requireCommunityInScope(req.getCommunity());
        String operator = scopeGuard.resolveOperator(req.getOperator());
        LocalDateTime now = LocalDateTime.now();
        int newTotal = req.getNewTotalAmount();

        QuotaBalance balance = balanceMapper.selectOne(new QueryWrapper<QuotaBalance>()
                .eq("community", req.getCommunity()));
        int oldTotal;
        if (balance == null) {
            // 开户：首次为小区配置配额
            balance = new QuotaBalance();
            balance.setCommunity(req.getCommunity());
            balance.setTotalAmount(newTotal);
            balance.setOccupiedAmount(0);
            balance.setReservedAmount(0);
            balance.setRequestNo(req.getRequestNo());
            balance.setOperator(operator);
            balance.setBizTime(now);
            balance.setDataVersion(1);
            balance.setUpdatedAt(now);
            try {
                balanceMapper.insert(balance);
            } catch (DuplicateKeyException e) {
                throw new IllegalArgumentException("小区配额已存在，请改用调整");
            }
            oldTotal = 0;
        } else {
            int floor = balance.getOccupiedAmount() + balance.getReservedAmount();
            if (newTotal < floor) {
                throw new IllegalArgumentException("新配额 " + newTotal + " 不能低于已实扣 + 预占中 = "
                        + floor + "（会击穿在途单）");
            }
            if (balanceMapper.adjustTotal(req.getCommunity(), newTotal,
                    req.getRequestNo(), operator, now) == 0) {
                throw new IllegalArgumentException("配额调整失败：新配额不能低于已实扣 + 预占中");
            }
            oldTotal = balance.getTotalAmount();
            balance = reloadBalance(req.getCommunity());
        }
        writeJournal(balance, QuotaJournal.TYPE_ADJUST, null,
                newTotal - oldTotal, 0, 0, req.getRequestNo(), operator, now);
        log.info("出租率配额调整 community={} {} -> {}（已实扣={} 预占中={} 余量={}）operator={} requestNo={}",
                req.getCommunity(), oldTotal, newTotal, balance.getOccupiedAmount(),
                balance.getReservedAmount(), balance.available(), operator, req.getRequestNo());
        scopeGuard.audit("apartment:quota:adjust", req.getRequestNo(),
                "community=" + req.getCommunity() + ",oldTotal=" + oldTotal + ",newTotal=" + newTotal, null);
        return balance;
    }

    /** 余量查询：返回配额/已实扣/预占中/余量，逐查询落放行审计。 */
    public QuotaBalance getBalance(String community) {
        QuotaBalance balance = balanceMapper.selectOne(new QueryWrapper<QuotaBalance>()
                .eq("community", community));
        if (balance == null) {
            throw new IllegalArgumentException("小区尚未配置出租率配额: " + community);
        }
        scopeGuard.requireCommunityInScope(community);
        scopeGuard.audit("apartment:quota:view", null,
                "quotaBalance community=" + community, 1);
        return balance;
    }

    /** 预占单检索：可按小区/状态/业务单过滤；SELF 仅本人申请、SCOPE 授权小区、ALL 全部。 */
    public List<QuotaHold> searchHolds(String community, String status, String bizRef) {
        QueryWrapper<QuotaHold> query = new QueryWrapper<>();
        query.eq(StringUtils.hasText(community), "community", community);
        query.eq(StringUtils.hasText(status), "status", status);
        query.eq(StringUtils.hasText(bizRef), "biz_ref", bizRef);
        applyHoldScope(query);
        query.orderByDesc("id");
        List<QuotaHold> holds = holdMapper.selectList(query);
        scopeGuard.audit("apartment:quota:view", null,
                "quotaHolds community=" + community + ",status=" + status + ",bizRef=" + bizRef,
                holds.size());
        return holds;
    }

    /** 额度流水：每一次余量/配额变化可查（按小区，可选变化类型/业务单）。 */
    public List<QuotaJournal> searchJournal(String community, String changeType, String bizRef) {
        if (!StringUtils.hasText(community)) {
            throw new IllegalArgumentException("查询额度流水必须指定小区");
        }
        scopeGuard.requireCommunityInScope(community);
        QueryWrapper<QuotaJournal> query = new QueryWrapper<>();
        query.eq("community", community);
        query.eq(StringUtils.hasText(changeType), "change_type", changeType);
        query.eq(StringUtils.hasText(bizRef), "biz_ref", bizRef);
        query.orderByAsc("id");
        List<QuotaJournal> rows = journalMapper.selectList(query);
        scopeGuard.audit("apartment:quota:view", null,
                "quotaJournal community=" + community + ",changeType=" + changeType + ",bizRef=" + bizRef,
                rows.size());
        return rows;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void releaseReservation(QuotaHold hold, String changeType, String reason,
                                    String requestNo, String operator, LocalDateTime now) {
        if (balanceMapper.release(hold.getCommunity(), hold.getReserveAmount(),
                requestNo, operator, now) == 0) {
            throw new IllegalStateException("账上预占额不足，释放失败（数据异常）: " + hold.getHoldNo());
        }
        QuotaBalance after = reloadBalance(hold.getCommunity());
        writeJournal(after, changeType, hold,
                0, 0, -hold.getReserveAmount(), requestNo, operator, now);
    }

    private void writeJournal(QuotaBalance after, String changeType, QuotaHold hold,
                              int deltaTotal, int deltaOccupied, int deltaReserved,
                              String requestNo, String operator, LocalDateTime now) {
        QuotaJournal j = new QuotaJournal();
        j.setCommunity(after.getCommunity());
        j.setChangeType(changeType);
        j.setHoldId(hold == null ? null : hold.getId());
        j.setHoldNo(hold == null ? null : hold.getHoldNo());
        j.setBizRef(hold == null ? null : hold.getBizRef());
        j.setDeltaTotal(deltaTotal);
        j.setDeltaOccupied(deltaOccupied);
        j.setDeltaReserved(deltaReserved);
        j.setTotalAfter(after.getTotalAmount());
        j.setOccupiedAfter(after.getOccupiedAmount());
        j.setReservedAfter(after.getReservedAmount());
        j.setRequestNo(requestNo);
        j.setOperator(operator);
        j.setBizTime(now);
        journalMapper.insert(j);
    }

    private QuotaHold requireHold(String holdNo) {
        QuotaHold hold = holdMapper.selectOne(new QueryWrapper<QuotaHold>().eq("hold_no", holdNo));
        if (hold == null) {
            throw new IllegalArgumentException("预占单不存在: " + holdNo);
        }
        requireOperable(hold);
        return hold;
    }

    /** 写操作范围校验：SCOPE 校验小区，SELF 仅可操作本人申请的预占单。 */
    private void requireOperable(QuotaHold hold) {
        scopeGuard.requireCommunityInScope(hold.getCommunity());
        scopeGuard.requireSelfOperated(hold.getOperator());
    }

    private QuotaHold findActiveByBizRef(String bizRef) {
        return holdMapper.selectOne(new QueryWrapper<QuotaHold>()
                .eq("biz_ref", bizRef)
                .in("status", QuotaHold.STATUS_HELD, QuotaHold.STATUS_CONFIRMED)
                .last("LIMIT 1"));
    }

    private void applyHoldScope(QueryWrapper<QuotaHold> query) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            query.eq("operator", user.getUsername());
            return;
        }
        if (user.getScope().getCommunities().isEmpty()) {
            query.apply("1 = 0");
        } else {
            query.in("community", user.getScope().getCommunities());
        }
    }

    private QuotaBalance reloadBalance(String community) {
        QuotaBalance after = balanceMapper.selectOne(new QueryWrapper<QuotaBalance>()
                .eq("community", community));
        if (after == null) {
            throw new IllegalStateException("额度账不存在: " + community);
        }
        return after;
    }

    private static void restoreContract(String previous) {
        if (previous == null) {
            TraceContext.clearContract();
        } else {
            TraceContext.bindContract(previous);
        }
    }
}
