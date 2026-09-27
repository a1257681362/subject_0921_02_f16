package com.evops.apartment.esign.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 电子签章平台回调的随机号（nonce）使用记录：同一 nonce 在防重放窗口内只许出现一次。
 *
 * - 验签通过、时效通过后，先按唯一键抢占插入本记录：插入冲突即重放，拒绝请求；
 * - 窗口（默认 15 分钟）由 {@code expire_at} 界定，到期记录由定时任务清理（只删不影响在窗数据）；
 * - 不依赖单机内存：多实例部署时由数据库唯一约束兜底，任一节点都识别同一 nonce。
 */
@TableName("t_esign_nonce")
public class ESignNonce {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 平台请求头 X-EV-Nonce。 */
    private String nonce;
    /** 回调携带的业务幂等号（签署完成回调为 EVOPS-合同号），便于排查重放来源。 */
    private String bizNo;
    /** 请求时间戳（毫秒），取平台入参、以服务端时钟校验后留存。 */
    private Long requestTimestamp;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime seenAt;
    /** 防重放窗口截止时间；晚于此时间的同 nonce 记录已被清理，可再次出现（但请求早已超 8 分钟时效）。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public Long getRequestTimestamp() { return requestTimestamp; }
    public void setRequestTimestamp(Long requestTimestamp) { this.requestTimestamp = requestTimestamp; }
    public LocalDateTime getSeenAt() { return seenAt; }
    public void setSeenAt(LocalDateTime seenAt) { this.seenAt = seenAt; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
}
