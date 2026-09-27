package com.evops.apartment.esign;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 电子签章平台 HMAC-SHA256 签名/验签工具，出站（{@link HttpESignPlatformClient}）
 * 与入站（平台回调）共用同一套规矩，同一把密钥、同一种算法、同一个签名串：
 *
 * <pre>
 * 签名串 = METHOD\nPATH\ntimestamp\nnonce\nbodyMd5
 * bodyMd5 = 请求体原文（UTF-8）的 MD5 十六进制；无请求体时为 md5("")
 * 签名   = Base64(HMAC_SHA256(accessSecret, 签名串))
 * </pre>
 *
 * 全部参数随请求体整体参与摘要：空值字段以 {@code ""} 保留在报文中，
 * 字段一旦缺省/改动，bodyMd5 即变、验签不过——空值同样被签名覆盖。
 */
public final class ESignSignature {

    private ESignSignature() {
    }

    /** 请求体 MD5（十六进制小写）。入参为已序列化的报文文本，无报文传空串。 */
    public static String md5Hex(String rawText) {
        return md5Hex(rawText == null ? new byte[0] : rawText.getBytes(StandardCharsets.UTF_8));
    }

    /** 请求体 MD5（十六进制小写），直接对报文字节摘要，避免解码歧义。 */
    public static String md5Hex(byte[] rawBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(rawBytes == null ? new byte[0] : rawBytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ESignException("MD5 计算失败", e);
        }
    }

    /** 组装签名串：METHOD\nPATH\ntimestamp\nnonce\nbodyMd5。 */
    public static String canonicalString(String method, String path, String timestamp,
                                         String nonce, String bodyMd5) {
        return method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyMd5;
    }

    /** 以共享密钥对签名串计算 HMAC-SHA256，输出 Base64。 */
    public static String hmacSha256Base64(String accessSecret, String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(accessSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ESignException("电子签章签名计算失败", e);
        }
    }

    /** 一步出签名：METHOD/PATH/timestamp/nonce/bodyMd5 按规矩拼接后 HMAC-SHA256。 */
    public static String sign(String accessSecret, String method, String path,
                              String timestamp, String nonce, String bodyMd5) {
        return hmacSha256Base64(accessSecret, canonicalString(method, path, timestamp, nonce, bodyMd5));
    }

    /**
     * 定长比较两段签名，避免计时侧信道；任一为 null 视为不匹配。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
