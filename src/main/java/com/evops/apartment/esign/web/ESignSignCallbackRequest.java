package com.evops.apartment.esign.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 平台「签署完成」回调请求体。报文字段（含空值字段）全部参与签名：
 * 验签在过滤器对原始报文做 MD5，本 DTO 只用于验签通过后的业务解析。
 *
 * <pre>
 * {
 *   "event": "SIGN_COMPLETED",      // 事件类型，缺省按签署完成处理（空值同样被签名）
 *   "bizNo": "EVOPS-HT-xxx",        // 业务幂等号 = EVOPS-合同号（必填）
 *   "flowNo": "FLOW-EVOPS-HT-xxx-...", // 平台签署流水号（选填，带则与平台记录核对）
 *   "signedAt": "2026-09-24 18:00:00", // 平台侧签署完成时间（选填，仅留痕）
 *   "remark": ""                    // 预留字段：即便为空也参与签名
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ESignSignCallbackRequest {
    private String event;
    private String bizNo;
    private String flowNo;
    private String signedAt;
    private String remark;

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public String getFlowNo() { return flowNo; }
    public void setFlowNo(String flowNo) { this.flowNo = flowNo; }
    public String getSignedAt() { return signedAt; }
    public void setSignedAt(String signedAt) { this.signedAt = signedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
