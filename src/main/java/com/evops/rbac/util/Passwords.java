package com.evops.rbac.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 口令摘要：SHA-256 十六进制。首版账号库本地存放，不引入额外加密设施；
 * 上线后接入统一认证时仅需替换此类与登录组装逻辑。
 */
public final class Passwords {
    private Passwords() {
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static boolean matches(String raw, String hash) {
        return hash != null && hash.equalsIgnoreCase(sha256(raw));
    }
}
