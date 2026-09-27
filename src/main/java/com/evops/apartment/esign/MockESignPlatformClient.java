package com.evops.apartment.esign;

import com.evops.apartment.mask.SensitiveFields;
import com.evops.apartment.mask.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内电子签章平台（演示/测试/默认实现）：
 *
 * - 零外部依赖，下周对接演示开箱即用；
 * - 严格按 {@link ESignClient} 的幂等约定实现：所有写操作以业务幂等号去重，
 *   断点重跑/取消后重跑不会产生重复用印、重复文书；
 * - 发起签署后默认 {@code signWaitMs} 毫秒自动完成签署（模拟租客在 H5 页面签字），
 *   也可通过 {@link #manualCompleteSign(String)} 由演示页手动点“完成签署”；
 * - {@link #injectFailure} 用于故障演练与自动化测试（模拟平台超时/失败后断点重跑），
 *   生产不要调用。
 *
 * 切换真实平台：evops.esign.provider=http，仅实现层替换，任务步骤不变。
 */
@Component
@ConditionalOnProperty(name = "evops.esign.provider", havingValue = "mock", matchIfMissing = true)
public class MockESignPlatformClient implements ESignClient {
    private static final Logger log = LoggerFactory.getLogger(MockESignPlatformClient.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ESignProperties properties;
    private final Map<String, Flow> flows = new ConcurrentHashMap<>();
    /** key = bizNo|stage，value = 剩余失败次数；用于故障演练/测试。 */
    private final Map<String, Integer> failurePlan = new ConcurrentHashMap<>();
    /** 挂起自动签署的业务号：只等演示页/测试手动完成，用于确定地演示“等待租客签署”。 */
    private final Set<String> heldSign = ConcurrentHashMap.newKeySet();

    public MockESignPlatformClient(ESignProperties properties) {
        this.properties = properties;
    }

    private static final class Flow {
        final String bizNo;
        final String flowNo;
        final String subject;
        final long createdAtMs = System.currentTimeMillis();
        volatile String docToken;
        volatile String title;
        volatile String content;
        volatile String signerName;
        volatile String signerIdCard;
        volatile String signUrl;
        volatile long signStartAtMs;
        volatile boolean signed;
        volatile boolean sealed;
        volatile boolean fetched;

        private Flow(String bizNo, String flowNo, String subject) {
            this.bizNo = bizNo;
            this.flowNo = flowNo;
            this.subject = subject;
        }
    }

    @Override
    public void ping() {
        sleepQuietly(properties.getMock().getStepDelayMs());
        failIfPlanned(null, "ping");
        log.info("[mock-esign] ping ok");
    }

    @Override
    public String createSealRequest(String bizNo, String subject) {
        sleepQuietly(properties.getMock().getStepDelayMs());
        failIfPlanned(bizNo, "sealRequest");
        // 幂等：同 bizNo 永远返回第一次创建的流水号
        Flow flow = flows.computeIfAbsent(bizNo,
                k -> new Flow(k, "FLOW-" + bizNo + "-" + LocalDateTime.now().format(TS), subject));
        log.info("[mock-esign] createSealRequest bizNo={} -> {} (幂等命中={})",
                bizNo, flow.flowNo, flow.subject != null);
        return flow.flowNo;
    }

    @Override
    public String createDocument(String flowNo, String bizNo, String title, String content) {
        sleepQuietly(properties.getMock().getStepDelayMs());
        failIfPlanned(bizNo, "document");
        Flow flow = requireFlow(bizNo, flowNo);
        synchronized (flow) {
            if (flow.docToken == null) {
                flow.docToken = "DOC-" + flowNo;
                flow.title = title;
                flow.content = content;
            }
        }
        log.info("[mock-esign] createDocument flowNo={} -> {} (幂等)", flowNo, flow.docToken);
        return flow.docToken;
    }

    @Override
    public String startTenantSign(String flowNo, String bizNo, String docToken,
                                  String signerName, String signerIdCard) {
        sleepQuietly(properties.getMock().getStepDelayMs());
        failIfPlanned(bizNo, "startSign");
        Flow flow = requireFlow(bizNo, flowNo);
        synchronized (flow) {
            if (flow.signUrl == null) {
                flow.signerName = signerName;
                flow.signerIdCard = signerIdCard;
                flow.signStartAtMs = System.currentTimeMillis();
                flow.signUrl = "/demo/sign.html?flowNo=" + flowNo;
            }
        }
        // apt-12：日志输出与接口/导出同一套脱敏规则，签署人姓名不明文入日志
        log.info("[mock-esign] startTenantSign flowNo={} signer={} url={} (幂等)",
                flowNo, SensitiveFields.maskValue(SensitiveType.NAME, signerName), flow.signUrl);
        return flow.signUrl;
    }

    @Override
    public String querySignStatus(String flowNo) {
        Flow flow = findByFlowNo(flowNo);
        if (flow == null) {
            throw new ESignException("签署流水不存在: " + flowNo);
        }
        failIfPlanned(flow.bizNo, "query");
        boolean autoDone = !flow.signed && !heldSign.contains(flow.bizNo)
                && System.currentTimeMillis() - flow.signStartAtMs >= properties.getMock().getSignWaitMs();
        if (autoDone) {
            flow.signed = true;
            log.info("[mock-esign] 模拟租客在 H5 完成签署 flowNo={}", flowNo);
        }
        return flow.signed ? "SIGNED" : "SIGNING";
    }

    @Override
    public String queryFlowStatus(String flowNo) {
        Flow flow = findByFlowNo(flowNo);
        if (flow == null) {
            throw new ESignException("签署流水不存在: " + flowNo);
        }
        // 与 querySignStatus 共用自动签署推进逻辑
        querySignStatus(flowNo);
        if (!flow.signed) {
            return "SIGNING";
        }
        if (!flow.sealed) {
            return "SIGNED";
        }
        return flow.fetched ? "FINISHED" : "SEALED";
    }

    @Override
    public void seal(String flowNo, String bizNo) {
        sleepQuietly(properties.getMock().getStepDelayMs());
        failIfPlanned(bizNo, "seal");
        Flow flow = requireFlow(bizNo, flowNo);
        if (!flow.signed) {
            throw new ESignException("签署未完成，平台拒绝用印: " + flowNo);
        }
        flow.sealed = true;
        log.info("[mock-esign] seal flowNo={} (幂等重复调用安全)", flowNo);
    }

    @Override
    public SignedFile fetchSignedFile(String flowNo, String bizNo, String docToken) {
        sleepQuietly(properties.getMock().getStepDelayMs());
        failIfPlanned(bizNo, "fetch");
        Flow flow = requireFlow(bizNo, flowNo);
        if (!flow.sealed) {
            throw new ESignException("平台尚未盖章，无法回传文件: " + flowNo);
        }
        flow.fetched = true;
        String fileName = flow.bizNo + "-租赁合同已签章.txt";
        String content = buildFileContent(flow);
        log.info("[mock-esign] fetchSignedFile flowNo={} -> {}", flowNo, fileName);
        return new SignedFile(fileName, content);
    }

    // ---------------- 演示页 / 运维辅助 ----------------

    /**
     * apt-9 平台签署完成回调入站（mock）：把对应流程翻转为已签署，效果等同租客在 H5 完成签字。
     * 幂等：已是签署态的流程重复回调不报错（与 {@link #manualCompleteSign} 一致但按 bizNo 校验）。
     */
    @Override
    public void applySignCompletedCallback(String bizNo, String flowNo) {
        Flow flow = flows.get(bizNo);
        if (flow == null || (flowNo != null && !flowNo.isEmpty() && !flow.flowNo.equals(flowNo))) {
            throw new ESignException("平台回调业务号与流水号不匹配: bizNo=" + bizNo + ", flowNo=" + flowNo);
        }
        heldSign.remove(bizNo);
        if (!flow.signed) {
            flow.signed = true;
            log.info("[mock-esign] 收到平台签署完成回调，流程置为已签署 bizNo={} flowNo={}", bizNo, flowNo);
        } else {
            log.info("[mock-esign] 平台签署完成回调幂等命中（已签署）bizNo={}", bizNo);
        }
    }

    /** 演示页“租客手动完成签署”按钮调用。 */
    public void manualCompleteSign(String flowNo) {
        Flow flow = findByFlowNo(flowNo);
        if (flow == null) {
            throw new ESignException("签署流水不存在: " + flowNo);
        }
        heldSign.remove(flow.bizNo);
        flow.signed = true;
        log.info("[mock-esign] 演示页手动完成签署 flowNo={}", flowNo);
    }

    /**
     * 挂起指定业务号的自动签署：只在 {@link #manualCompleteSign} 后才签署。
     * 用于演示“等待租客签署”环节与取消/重跑测试（生产不调用）。
     */
    public void holdSign(String bizNo) {
        heldSign.add(bizNo);
    }

    /** 演示页：查看当前模拟平台内的签署流程（不返回文书全文）。 */
    public List<Map<String, String>> listFlows() {
        return flows.values().stream()
                .map(f -> {
                    Map<String, String> m = new java.util.LinkedHashMap<>();
                    m.put("bizNo", f.bizNo);
                    m.put("flowNo", f.flowNo);
                    m.put("docToken", f.docToken);
                    m.put("status", !f.signed ? "SIGNING" : !f.sealed ? "SIGNED" : f.fetched ? "FINISHED" : "SEALED");
                    m.put("signer", f.signerName);
                    m.put("signUrl", f.signUrl);
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 故障注入：指定业务号在指定阶段再失败 times 次（ping 阶段 bizNo 传 null 对所有任务生效）。
     * stage 取值：ping/sealRequest/document/startSign/query/seal/fetch。
     */
    public void injectFailure(String bizNo, String stage, int times) {
        failurePlan.put(failureKey(bizNo, stage), times);
    }

    /** 清除该业务号的全部故障注入（重跑前调用）。 */
    public void clearFailure(String bizNo) {
        failurePlan.keySet().removeIf(k -> k.startsWith(bizNo + "|") || k.equals("null|ping"));
    }

    // ---------------- 内部 ----------------

    private String buildFileContent(Flow flow) {
        String digestSource = flow.bizNo + "|" + flow.flowNo + "|" + (flow.docToken == null ? "" : flow.docToken)
                + "|" + nullToEmpty(flow.signerName) + "|" + nullToEmpty(flow.signerIdCard)
                + "|" + nullToEmpty(flow.title);
        String digest = sha256Base64(digestSource);
        return "电子签章平台（模拟）已签署文件凭证\n"
                + "================================\n"
                + "业务幂等号: " + flow.bizNo + "\n"
                + "签署流水号: " + flow.flowNo + "\n"
                + "文书标识:   " + flow.docToken + "\n"
                + "签署人:     " + nullToEmpty(flow.signerName) + " " + nullToEmpty(flow.signerIdCard) + "\n"
                + "文书标题:   " + nullToEmpty(flow.title) + "\n"
                + "平台盖章:   已完成\n"
                + "文书摘要:   " + digest + "\n"
                + "出具时间:   " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Base64(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Flow requireFlow(String bizNo, String flowNo) {
        Flow flow = flows.get(bizNo);
        if (flow == null || !flow.flowNo.equals(flowNo)) {
            throw new ESignException("平台流水与业务号不匹配: bizNo=" + bizNo + ", flowNo=" + flowNo);
        }
        return flow;
    }

    private Flow findByFlowNo(String flowNo) {
        return flows.values().stream().filter(f -> f.flowNo.equals(flowNo)).findFirst().orElse(null);
    }

    private void failIfPlanned(String bizNo, String stage) {
        String key = failureKey(bizNo, stage);
        Integer remaining = failurePlan.get(key);
        if (remaining != null && remaining > 0) {
            failurePlan.put(key, remaining - 1);
            throw new ESignException("模拟平台故障：" + stage + " 暂时不可用（故障注入剩余 " + (remaining - 1) + " 次）");
        }
    }

    private String failureKey(String bizNo, String stage) {
        return bizNo + "|" + stage;
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 仅供演示页展示平台收到的文书正文。 */
    public String getDocumentContent(String flowNo) {
        Flow flow = findByFlowNo(flowNo);
        return flow == null ? null : flow.content;
    }

    /** 仅供需要等待平台清理的测试使用。 */
    public void resetForTest() {
        flows.clear();
        failurePlan.clear();
        heldSign.clear();
    }
}
