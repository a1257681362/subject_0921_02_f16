package com.evops.apartment.esign;

/**
 * 电子签章平台对接端口。任务步骤只依赖本接口，与具体平台解耦：
 * 演示用 {@code MockESignPlatformClient}，接真实平台换 {@code HttpESignPlatformClient}。
 *
 * 幂等约定（断点重跑安全的关键）：
 * 所有写操作以业务幂等号（合同号派生：{@code EVOPS-合同号}）去重，
 * 同一幂等号重复调用返回第一次的结果，不在平台侧产生重复用印/重复文书。
 */
public interface ESignClient {

    /** 联通性/凭证自检，演示前与启动后可调用。 */
    void ping();

    /**
     * 用印申请：创建签署流程，返回平台流水号。
     * 同 bizNo 重复调用返回同一流水号。
     */
    String createSealRequest(String bizNo, String subject);

    /** 生成签署文书，返回文书标识；同 (bizNo) 幂等。 */
    String createDocument(String flowNo, String bizNo, String title, String content);

    /** 发起租客签署，返回签署页地址；签署方与文书已确定，重复调用不重复发起。 */
    String startTenantSign(String flowNo, String bizNo, String docToken,
                           String signerName, String signerIdCard);

    /**
     * 查询签署状态：{@code SIGNING} 签署中 / {@code SIGNED} 已签署。
     * 任务侧轮询本接口，签署未完成不算失败。
     */
    String querySignStatus(String flowNo);

    /**
     * 查询整条签署流程状态：SIGNING / SIGNED / SEALED / FINISHED。
     * 用于盖章后复核，幂等只读。
     */
    String queryFlowStatus(String flowNo);

    /** 平台盖章：签署完成后调用，重复调用幂等。 */
    void seal(String flowNo, String bizNo);

    /** 回传已签署文件，重复调用返回同一文件。 */
    SignedFile fetchSignedFile(String flowNo, String bizNo, String docToken);

    /**
     * 平台「签署完成」回调入站（apt-9）：平台主动通知租客已在 H5 签字。
     *
     * - mock 提供方据此把内存流程翻转为已签署（否则只能等 signWaitMs 自动签署）；
     * - 真实平台（http）回调即其自身权威状态，下一次 {@link #querySignStatus} 自然返回 SIGNED，
     *   故默认空实现。
     *
     * 实现必须幂等：同一签署完成回调重复到达不得报错、不得产生重复状态。
     * 业务号/流水号不匹配应抛 {@link ESignException}，由回调服务转为业务拒绝。
     */
    default void applySignCompletedCallback(String bizNo, String flowNo) {
    }
}
