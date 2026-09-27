package com.evops.apartment.task.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.entity.PropertyLedger;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.mapper.PropertyLedgerMapper;
import com.evops.apartment.pinyin.PinyinIndex;
import com.evops.apartment.pinyin.PinyinIndexer;
import com.evops.apartment.task.dto.SignArchiveCheckpoint;
import com.evops.apartment.task.dto.SignArchivePayload;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.entity.LeaseSignArchive;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import com.evops.apartment.task.mapper.LeaseSignArchiveMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 签约归档各步骤的库内操作。每个方法都是独立短事务（REQUIRES_NEW）：
 * 步骤库内写入与进度推进分离，业务事务回滚不会污染进度；全部方法按业务号幂等，
 * 进度推进前宕机导致的重入不产生重复数据（合同号 uk / 先查后写双保险）。
 */
@Component
public class SignArchiveBusinessOps {
    private final LeaseMapper leaseMapper;
    private final PropertyLedgerMapper ledgerMapper;
    private final LeaseSignArchiveMapper archiveMapper;
    private final AsyncTaskMapper taskMapper;

    public SignArchiveBusinessOps(LeaseMapper leaseMapper,
                                  PropertyLedgerMapper ledgerMapper,
                                  LeaseSignArchiveMapper archiveMapper,
                                  AsyncTaskMapper taskMapper) {
        this.leaseMapper = leaseMapper;
        this.ledgerMapper = ledgerMapper;
        this.archiveMapper = archiveMapper;
        this.taskMapper = taskMapper;
    }

    /** 步骤 2：核验房源台账存在，返回小区名（找不到抛异常，任务 FAILED 在该步）。 */
    @Transactional(readOnly = true)
    public String verifyProperty(SignArchivePayload payload) {
        Long count = ledgerMapper.selectCount(new QueryWrapper<PropertyLedger>()
                .eq("property_code", payload.getPropertyCode())
                .eq("entry_type", PropertyLedger.TYPE_REGISTER));
        if (count == null || count == 0) {
            throw new IllegalStateException("房源台账中不存在该房源，无法签约: " + payload.getPropertyCode());
        }
        PropertyLedger ledger = ledgerMapper.selectOne(new QueryWrapper<PropertyLedger>()
                .eq("property_code", payload.getPropertyCode())
                .eq("entry_type", PropertyLedger.TYPE_REGISTER)
                .orderByAsc("id").last("LIMIT 1"));
        return ledger.getCommunity();
    }

    /**
     * 步骤 6：租约建档落库。幂等：同合同号已有租约（上次尝试已插入）时直接回读，不重复 insert。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long createLeaseIfAbsent(AsyncTask task, SignArchivePayload p) {
        Lease existing = leaseMapper.selectOne(new QueryWrapper<Lease>().eq("contract_no", p.getContractNo()));
        if (existing != null) {
            return existing.getId();
        }
        Lease lease = new Lease();
        lease.setContractNo(p.getContractNo());
        lease.setPropertyCode(p.getPropertyCode());
        lease.setTenantName(p.getTenantName());
        lease.setTenantPhone(p.getTenantPhone());
        lease.setTenantIdCard(p.getTenantIdCard());
        lease.setLeaseStart(p.getLeaseStart());
        lease.setLeaseEnd(p.getLeaseEnd());
        lease.setMonthlyRent(p.getMonthlyRent());
        lease.setDeposit(p.getDeposit());
        // remark 在提交接口已过敏感词（打码/拦截），此处落处置后的文本
        lease.setRemark(p.getRemark());
        lease.setStatus(Lease.STATUS_ACTIVE);
        // apt-11：拼音冗余索引与租约行同一 REQUIRES_NEW 事务生成，口径与接口建档一致
        PinyinIndex pinyin = PinyinIndexer.build(p.getTenantName());
        lease.setTenantNamePyFull(pinyin.getFull());
        lease.setTenantNamePyInitials(pinyin.getInitials());
        lease.setRequestNo(task.getRequestNo());
        lease.setOperator(task.getOperator());
        lease.setBizTime(LocalDateTime.now());
        lease.setDataVersion(1);
        leaseMapper.insert(lease);
        return lease.getId();
    }

    /** 步骤 7：回读复核租约关键字段。 */
    @Transactional(readOnly = true)
    public void verifyLease(long leaseId, SignArchivePayload p) {
        Lease lease = leaseMapper.selectById(leaseId);
        if (lease == null) {
            throw new IllegalStateException("租约回读失败: id=" + leaseId);
        }
        if (!Lease.STATUS_ACTIVE.equals(lease.getStatus())) {
            throw new IllegalStateException("租约状态异常，期望 ACTIVE，实际 " + lease.getStatus());
        }
        if (!p.getContractNo().equals(lease.getContractNo())
                || p.getMonthlyRent().compareTo(lease.getMonthlyRent()) != 0) {
            throw new IllegalStateException("租约回读字段与签约数据不一致: " + p.getContractNo());
        }
    }

    /**
     * 步骤 11：建立平台签署链路后，登记归档行（SIGNING）。
     * 幂等：同合同号归档行已存在则回读 id，不重复插入。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long createArchiveIfAbsent(AsyncTask task, SignArchivePayload p, SignArchiveCheckpoint cp) {
        LeaseSignArchive existing = findByContract(p.getContractNo());
        if (existing != null) {
            return existing.getId();
        }
        LeaseSignArchive archive = new LeaseSignArchive();
        archive.setContractNo(p.getContractNo());
        archive.setLeaseId(cp.getLeaseId());
        archive.setPlatformFlowNo(cp.getFlowNo());
        archive.setPlatformDocToken(cp.getDocToken());
        archive.setSignStatus(LeaseSignArchive.SIGN_SIGNING);
        archive.setRequestNo(task.getRequestNo());
        archive.setOperator(task.getOperator());
        archive.setBizTime(LocalDateTime.now());
        archive.setDataVersion(1);
        archiveMapper.insert(archive);
        return archive.getId();
    }

    /** 步骤 13：租客签署完成回写归档（SIGNING -> SIGNED）。幂等：已是 SIGNED 之后的状态不再回退。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markArchiveSigned(long archiveId) {
        LeaseSignArchive archive = archiveMapper.selectById(archiveId);
        if (archive == null) {
            throw new IllegalStateException("归档行不存在: id=" + archiveId);
        }
        if (LeaseSignArchive.SIGN_SIGNING.equals(archive.getSignStatus())) {
            archive.setSignStatus(LeaseSignArchive.SIGN_SIGNED);
            archive.setDataVersion(archive.getDataVersion() == null ? 1 : archive.getDataVersion() + 1);
            archive.setUpdatedAt(LocalDateTime.now());
            archiveMapper.updateById(archive);
        }
    }

    /** 步骤 15：平台盖章完成，归档行置 SEALED 并回写租约关联。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkArchiveToLease(long archiveId, long leaseId) {
        LeaseSignArchive archive = archiveMapper.selectById(archiveId);
        if (archive == null) {
            throw new IllegalStateException("归档行不存在: id=" + archiveId);
        }
        archive.setSignStatus(LeaseSignArchive.SIGN_SEALED);
        archive.setDataVersion(archive.getDataVersion() == null ? 1 : archive.getDataVersion() + 1);
        archive.setUpdatedAt(LocalDateTime.now());
        archiveMapper.updateById(archive);

        Lease lease = leaseMapper.selectById(leaseId);
        if (lease != null && lease.getSignArchiveId() == null) {
            lease.setSignArchiveId(archiveId);
            lease.setDataVersion(lease.getDataVersion() == null ? 1 : lease.getDataVersion() + 1);
            lease.setUpdatedAt(LocalDateTime.now());
            leaseMapper.updateById(lease);
        }
    }

    /** 步骤 16：回传已签署文件并写入归档行（CLOB 凭证）。幂等：重复回传覆盖为同一内容。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSignedFile(long archiveId, String fileName, String fileContent) {
        LeaseSignArchive archive = archiveMapper.selectById(archiveId);
        if (archive == null) {
            throw new IllegalStateException("归档行不存在: id=" + archiveId);
        }
        archive.setFileName(fileName);
        archive.setFileContent(fileContent);
        archive.setDataVersion(archive.getDataVersion() == null ? 1 : archive.getDataVersion() + 1);
        archive.setUpdatedAt(LocalDateTime.now());
        archiveMapper.updateById(archive);
    }

    /** 步骤 17：文件完整性校验：非空且状态在 SEALED。 */
    @Transactional(readOnly = true)
    public void verifySignedFile(long archiveId) {
        LeaseSignArchive archive = archiveMapper.selectById(archiveId);
        if (archive == null || archive.getFileContent() == null || archive.getFileContent().isEmpty()) {
            throw new IllegalStateException("已签署文件缺失或内容为空: archiveId=" + archiveId);
        }
        if (!LeaseSignArchive.SIGN_SEALED.equals(archive.getSignStatus())
                && !LeaseSignArchive.SIGN_FINISHED.equals(archive.getSignStatus())) {
            throw new IllegalStateException("归档状态未到已盖章，不能校验文件: "
                    + (archive == null ? null : archive.getSignStatus()));
        }
    }

    /** 步骤 18：归档置 FINISHED + 签署完成时间。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeArchive(long archiveId) {
        LeaseSignArchive archive = archiveMapper.selectById(archiveId);
        if (archive == null) {
            throw new IllegalStateException("归档行不存在: id=" + archiveId);
        }
        if (!LeaseSignArchive.SIGN_FINISHED.equals(archive.getSignStatus())) {
            archive.setSignStatus(LeaseSignArchive.SIGN_FINISHED);
            archive.setSignedAt(LocalDateTime.now());
            archive.setDataVersion(archive.getDataVersion() == null ? 1 : archive.getDataVersion() + 1);
            archive.setUpdatedAt(LocalDateTime.now());
            archiveMapper.updateById(archive);
        }
    }

    /** 步骤 19：最终复核：归档 FINISHED、租约 ACTIVE 且关联已回写。 */
    @Transactional(readOnly = true)
    public LeaseSignArchive verifyFinalState(String contractNo, long leaseId, long archiveId) {
        LeaseSignArchive archive = archiveMapper.selectById(archiveId);
        if (archive == null || !LeaseSignArchive.SIGN_FINISHED.equals(archive.getSignStatus())) {
            throw new IllegalStateException("归档最终状态不是 FINISHED: archiveId=" + archiveId);
        }
        Lease lease = leaseMapper.selectById(leaseId);
        if (lease == null || !Lease.STATUS_ACTIVE.equals(lease.getStatus())
                || lease.getSignArchiveId() == null || !lease.getSignArchiveId().equals(archiveId)) {
            throw new IllegalStateException("租约归档关联复核失败: contractNo=" + contractNo);
        }
        return archive;
    }

    /** 断点上下文随产生步骤的业务事务一起提交（业务回滚则断点不推进）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistCheckpoint(Long taskId, String checkpointJson) {
        AsyncTask update = new AsyncTask();
        update.setId(taskId);
        update.setCheckpoint(checkpointJson);
        update.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(update);
    }

    public LeaseSignArchive findByContract(String contractNo) {
        return archiveMapper.selectOne(new QueryWrapper<LeaseSignArchive>().eq("contract_no", contractNo));
    }
}
