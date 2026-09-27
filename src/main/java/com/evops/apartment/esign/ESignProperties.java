package com.evops.apartment.esign;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 电子签章平台对接配置。
 *
 * provider=mock（默认）：进程内演示平台，零外部依赖，下周对接演示直接可用，
 *   并在 /esign-demo/** 提供模拟签署页；
 * provider=http：真实平台网关，HMAC 签名 + 超时控制，切换只改配置，任务步骤与接口不变。
 *
 * 平台回调 EVOPS（apt-9）：平台签署完成后主动通知，服务端按 {@code callback} 下的规矩强制
 * 验签 + 时效 + nonce 防重放；出站签名（{@link HttpESignPlatformClient}）与入站验签
 * 复用同一把 {@code access-key/access-secret}，双方按同样的密钥与算法对账。
 */
@ConfigurationProperties(prefix = "evops.esign")
public class ESignProperties {
    /** mock / http */
    private String provider = "mock";
    private String baseUrl = "http://esign-gateway.internal";
    private String accessKey = "";
    private String accessSecret = "";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;
    private final Mock mock = new Mock();
    private final Callback callback = new Callback();

    public static class Mock {
        /** 每次平台调用的模拟耗时（毫秒），演示时进度条可见的节奏来源。 */
        private long stepDelayMs = 200;
        /** 发起签署后，模拟平台自动签署完成的等待时间（毫秒）；演示页也可手动点“完成签署”。 */
        private long signWaitMs = 1500;

        public long getStepDelayMs() { return stepDelayMs; }
        public void setStepDelayMs(long stepDelayMs) { this.stepDelayMs = stepDelayMs; }
        public long getSignWaitMs() { return signWaitMs; }
        public void setSignWaitMs(long signWaitMs) { this.signWaitMs = signWaitMs; }
    }

    /**
     * 平台回调安全规矩。管家要求“把规矩定进系统里，不能靠自觉”，故默认强制开启：
     * 缺签名/错签名、时间戳与服务端时钟相差超过 {@code timestampMaxAgeSeconds}（默认 8 分钟）、
     * 同一 nonce 在 {@code nonceWindowMinutes}（默认 15 分钟）内重复，一律拒绝。
     */
    public static class Callback {
        /** 回调验签总开关。默认开启；关闭仅用于紧急排障（会放开平台入口，平时不可关）。 */
        private boolean enabled = true;
        /** 回调路径（过滤器只守这一条）。 */
        private String path = "/api/esign/callback/sign";
        /** 请求时间戳允许的最大偏移秒数：超过（过早或过晚）拒绝，默认 8 分钟。 */
        private long timestampMaxAgeSeconds = 8 * 60L;
        /** nonce 防重放窗口分钟数：同一 nonce 该窗口内只许出现一次，默认 15 分钟。 */
        private long nonceWindowMinutes = 15L;
        /** 过期 nonce 记录清理间隔毫秒（默认每分钟；记录到期即可清）。 */
        private long nonceCleanupIntervalMs = 60_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public long getTimestampMaxAgeSeconds() { return timestampMaxAgeSeconds; }
        public void setTimestampMaxAgeSeconds(long timestampMaxAgeSeconds) {
            this.timestampMaxAgeSeconds = timestampMaxAgeSeconds;
        }
        public long getNonceWindowMinutes() { return nonceWindowMinutes; }
        public void setNonceWindowMinutes(long nonceWindowMinutes) {
            this.nonceWindowMinutes = nonceWindowMinutes;
        }
        public long getNonceCleanupIntervalMs() { return nonceCleanupIntervalMs; }
        public void setNonceCleanupIntervalMs(long nonceCleanupIntervalMs) {
            this.nonceCleanupIntervalMs = nonceCleanupIntervalMs;
        }
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getAccessSecret() { return accessSecret; }
    public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public Mock getMock() { return mock; }
    public Callback getCallback() { return callback; }
}
