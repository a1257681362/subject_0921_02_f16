package com.evops.apartment.task.engine;

import com.evops.apartment.esign.ESignClient;
import com.evops.apartment.esign.SignedFile;
import com.evops.apartment.mask.SensitiveFields;
import com.evops.apartment.mask.SensitiveType;
import com.evops.apartment.task.TaskProperties;
import com.evops.apartment.task.dto.SignArchiveCheckpoint;
import com.evops.apartment.task.dto.SignArchivePayload;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.entity.LeaseSignArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 签约归档 20 个一致步骤（每步 +5%）。步骤边界即取消边界、提交边界、断点边界：
 *
 * <pre>
 *  1 平台连通自检            6 租约建档落库          11 归档行登记(SIGNING)  16 回传已签署文件
 *  2 核验房源台账            7 租约回读复核          12 等待租客签署(轮询)   17 文件完整性校验
 *  3 签约要素复核            8 生成签署文书          13 签署完成确认         18 归档置 FINISHED
 *  4 组装合同文书            9 发起租客签署          14 平台盖章+复核        19 最终一致性复核
 *  5 提交用印申请           10 签署链接送达确认      15 归档回写租约         20 收尾产出汇总
 * </pre>
 *
 * 幂等三重保险：已完成步骤由进度行确认不重跑；进度确认前宕机导致的重入，
 * 库内操作先查后写（合同号/归档 uk），平台写操作以 bizNo 业务幂等号去重。
 */
@Component
public class SignArchiveStepHandler implements TaskStepHandler {
    private static final Logger log = LoggerFactory.getLogger(SignArchiveStepHandler.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 步骤名（1 基），前端轮询可按 currentStep 展示当前环节。 */
    public static final List<String> STEP_NAMES = Collections.unmodifiableList(Arrays.asList(
            "平台连通自检", "核验房源台账", "签约要素复核", "组装合同文书", "提交用印申请",
            "租约建档落库", "租约回读复核", "生成签署文书", "发起租客签署", "签署链接送达确认",
            "归档行登记", "等待租客签署", "签署完成确认", "平台盖章", "归档回写租约",
            "回传已签署文件", "文件完整性校验", "归档完成", "最终一致性复核", "收尾"));

    private final ESignClient eSignClient;
    private final SignArchiveBusinessOps ops;
    private final ObjectMapper objectMapper;
    private final TaskProperties properties;

    public SignArchiveStepHandler(ESignClient eSignClient,
                                  SignArchiveBusinessOps ops,
                                  ObjectMapper objectMapper,
                                  TaskProperties properties) {
        this.eSignClient = eSignClient;
        this.ops = ops;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String type() {
        return AsyncTask.TYPE_SIGN_ARCHIVE;
    }

    @Override
    public int totalSteps() {
        return STEP_NAMES.size();
    }

    @Override
    public StepResult executeStep(AsyncTask task, int stepNo) {
        try {
            SignArchivePayload payload = parse(task.getPayload(), SignArchivePayload.class);
            SignArchiveCheckpoint cp = task.getCheckpoint() == null
                    ? new SignArchiveCheckpoint()
                    : parse(task.getCheckpoint(), SignArchiveCheckpoint.class);
            if (cp.getBizNo() == null) {
                cp.setBizNo(toBizNo(payload.getContractNo()));
            }

            switch (stepNo) {
                case 1:
                    eSignClient.ping();
                    return StepResult.advance();
                case 2: {
                    String community = ops.verifyProperty(payload);
                    cp.setCommunity(community);
                    saveCp(task, cp);
                    return StepResult.advance();
                }
                case 3:
                    validatePayload(payload);
                    return StepResult.advance();
                case 4:
                    // 文书内容由签约要素确定性组装，重跑结果一致，无需持久化
                    buildDocumentContent(payload, cp);
                    return StepResult.advance();
                case 5: {
                    String flowNo = eSignClient.createSealRequest(cp.getBizNo(),
                            "租赁合同-" + payload.getContractNo());
                    cp.setFlowNo(flowNo);
                    saveCp(task, cp);
                    log.info("签约归档任务 {} 用印申请成功 flowNo={}", task.getTaskNo(), flowNo);
                    return StepResult.advance();
                }
                case 6: {
                    long leaseId = ops.createLeaseIfAbsent(task, payload);
                    cp.setLeaseId(leaseId);
                    saveCp(task, cp);
                    return StepResult.advance();
                }
                case 7:
                    ops.verifyLease(cp.getLeaseId(), payload);
                    return StepResult.advance();
                case 8: {
                    String docToken = eSignClient.createDocument(cp.getFlowNo(), cp.getBizNo(),
                            payload.getContractNo() + "房屋租赁合同", buildDocumentContent(payload, cp));
                    cp.setDocToken(docToken);
                    saveCp(task, cp);
                    return StepResult.advance();
                }
                case 9: {
                    String signUrl = eSignClient.startTenantSign(cp.getFlowNo(), cp.getBizNo(),
                            cp.getDocToken(), payload.getTenantName(), payload.getTenantIdCard());
                    cp.setSignUrl(signUrl);
                    saveCp(task, cp);
                    return StepResult.advance();
                }
                case 10:
                    if (cp.getSignUrl() == null || cp.getSignUrl().isEmpty()) {
                        throw new IllegalStateException("签署链接为空，无法送达");
                    }
                    // apt-12：日志输出与接口/导出同一套脱敏规则，租客姓名不明文入日志
                    log.info("签约归档任务 {} 签署链接已送达租客 {}: {}",
                            task.getTaskNo(),
                            SensitiveFields.maskValue(SensitiveType.NAME, payload.getTenantName()),
                            cp.getSignUrl());
                    return StepResult.advance();
                case 11: {
                    long archiveId = ops.createArchiveIfAbsent(task, payload, cp);
                    cp.setArchiveId(archiveId);
                    saveCp(task, cp);
                    return StepResult.advance();
                }
                case 12: {
                    // 等待租客在 H5 签署：未完成不是失败，任务延后重领，进度停在 55%
                    String status = eSignClient.querySignStatus(cp.getFlowNo());
                    if ("SIGNED".equals(status)) {
                        return StepResult.advance();
                    }
                    return StepResult.waiting();
                }
                case 13:
                    ops.markArchiveSigned(cp.getArchiveId());
                    return StepResult.advance();
                case 14: {
                    eSignClient.seal(cp.getFlowNo(), cp.getBizNo());
                    String flowStatus = eSignClient.queryFlowStatus(cp.getFlowNo());
                    if (!"SEALED".equals(flowStatus) && !"FINISHED".equals(flowStatus)) {
                        throw new IllegalStateException("盖章后平台流程状态异常: " + flowStatus);
                    }
                    return StepResult.advance();
                }
                case 15:
                    ops.linkArchiveToLease(cp.getArchiveId(), cp.getLeaseId());
                    return StepResult.advance();
                case 16: {
                    SignedFile file = eSignClient.fetchSignedFile(
                            cp.getFlowNo(), cp.getBizNo(), cp.getDocToken());
                    ops.saveSignedFile(cp.getArchiveId(), file.getFileName(), file.getContent());
                    cp.setFileName(file.getFileName());
                    saveCp(task, cp);
                    return StepResult.advance();
                }
                case 17:
                    ops.verifySignedFile(cp.getArchiveId());
                    return StepResult.advance();
                case 18:
                    ops.finalizeArchive(cp.getArchiveId());
                    return StepResult.advance();
                case 19: {
                    LeaseSignArchive archive = ops.verifyFinalState(
                            payload.getContractNo(), cp.getLeaseId(), cp.getArchiveId());
                    cp.setSignedAt(archive.getSignedAt());
                    saveCp(task, cp);
                    return StepResult.advance();
                }
                case 20:
                    return StepResult.finished(buildResultJson(payload, cp));
                default:
                    throw new IllegalStateException("未知步骤: " + stepNo);
            }
        } catch (RuntimeException e) {
            // 平台故障、校验不通过等统一抛出，由引擎在当前步骤记 FAILED 断点
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("步骤 " + stepNo + " 执行失败: " + e.getMessage(), e);
        }
    }

    /** 等待签署时建议的下次认领时间。 */
    public LocalDateTime nextRunAfterWait() {
        return LocalDateTime.now().plusSeconds(Math.max(1, properties.getSignPollIntervalMs() / 1000));
    }

    /** 业务幂等号：同一合同的所有平台写操作固定同一 bizNo，平台侧据此去重。 */
    public static String toBizNo(String contractNo) {
        return "EVOPS-" + contractNo;
    }

    // ---------------- 内部 ----------------

    private void validatePayload(SignArchivePayload p) {
        if (p.getLeaseEnd().isBefore(p.getLeaseStart())) {
            throw new IllegalArgumentException("到期日期不能早于起租日期");
        }
        if (p.getMonthlyRent() == null || p.getMonthlyRent().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("月租金必须大于 0");
        }
        if (p.getDeposit() == null || p.getDeposit().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("押金不能为负");
        }
    }

    private String buildDocumentContent(SignArchivePayload p, SignArchiveCheckpoint cp) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("房屋租赁合同\n");
        sb.append("合同编号：").append(p.getContractNo()).append('\n');
        sb.append("房源编号：").append(p.getPropertyCode());
        if (cp.getCommunity() != null) {
            sb.append("（").append(cp.getCommunity()).append('）');
        }
        sb.append('\n');
        sb.append("承租人：").append(p.getTenantName());
        if (p.getTenantPhone() != null) {
            sb.append(' ').append(p.getTenantPhone());
        }
        if (p.getTenantIdCard() != null) {
            sb.append(' ').append(p.getTenantIdCard());
        }
        sb.append('\n');
        sb.append("租赁期限：").append(p.getLeaseStart()).append(" 至 ").append(p.getLeaseEnd()).append('\n');
        sb.append("月租金：").append(p.getMonthlyRent()).append(" 元\n");
        sb.append("押金：").append(p.getDeposit()).append(" 元\n");
        if (p.getRemark() != null && !p.getRemark().isEmpty()) {
            sb.append("约定事项：").append(p.getRemark()).append('\n');
        }
        sb.append("（本合同经电子签章平台签署盖章后生效）\n");
        return sb.toString();
    }

    private String buildResultJson(SignArchivePayload p, SignArchiveCheckpoint cp) {
        try {
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("contractNo", p.getContractNo());
            result.put("leaseId", cp.getLeaseId());
            result.put("archiveId", cp.getArchiveId());
            result.put("platformFlowNo", cp.getFlowNo());
            result.put("platformDocToken", cp.getDocToken());
            result.put("signUrl", cp.getSignUrl());
            result.put("signedFileName", cp.getFileName());
            result.put("signedAt", cp.getSignedAt() == null
                    ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : cp.getSignedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("结果摘要序列化失败", e);
        }
    }

    private void saveCp(AsyncTask task, SignArchiveCheckpoint cp) {
        ops.persistCheckpoint(task.getId(), writeJson(cp));
        task.setCheckpoint(writeJson(cp));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("断点上下文序列化失败", e);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("任务数据解析失败: " + e.getMessage(), e);
        }
    }

    /** 步骤名（供接口层展示）。 */
    public static String stepNameOf(int currentStep) {
        if (currentStep <= 0) {
            return "排队中";
        }
        if (currentStep >= STEP_NAMES.size()) {
            return "已完成";
        }
        return STEP_NAMES.get(currentStep); // currentStep 已完成，下一步索引 = currentStep
    }
}
