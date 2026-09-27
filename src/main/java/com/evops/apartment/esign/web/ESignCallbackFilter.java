package com.evops.apartment.esign.web;

import com.evops.apartment.esign.ESignProperties;
import com.evops.apartment.esign.ESignSignature;
import com.evops.apartment.esign.entity.ESignNonce;
import com.evops.apartment.esign.mapper.ESignNonceMapper;
import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 电子签章平台回调验签过滤器：把平台入站的安全规矩强制在入口，缺一条都进不了业务控制器。
 *
 * 校验顺序（任一不过即 401，且不消耗 nonce 之外的状态——验签未过不写 nonce）：
 * <ol>
 *   <li>四个头齐全：X-EV-Key / X-EV-Timestamp / X-EV-Nonce / X-EV-Signature，且 accessKey 与约定一致；</li>
 *   <li>时间戳以<b>服务端时钟</b>比对，相差超过 8 分钟（过早/过晚）拒绝；</li>
 *   <li>对原始报文整体算 bodyMd5，按 METHOD\nPATH\ntimestamp\nnonce\nbodyMd5 重算 HMAC-SHA256，
 *       定长比较签名；报文全部字段（含空值）都在摘要内，改任一字节即不通过；</li>
 *   <li>抢占写入 nonce（唯一约束），15 分钟窗口内同 nonce 再来即重放拒绝。</li>
 * </ol>
 *
 * 仅守护配置的回调路径；{@code evops.esign.callback.enabled=false} 时整体旁路
 * （紧急排障开关，平时保持开启）。
 */
public class ESignCallbackFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ESignCallbackFilter.class);

    public static final String HEADER_KEY = "X-EV-Key";
    public static final String HEADER_TIMESTAMP = "X-EV-Timestamp";
    public static final String HEADER_NONCE = "X-EV-Nonce";
    public static final String HEADER_SIGNATURE = "X-EV-Signature";

    /** 回调报文很小，超过 1MB 直接拒绝，避免异常报文耗内存。 */
    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final int MAX_NONCE_LEN = 128;

    private final ESignProperties properties;
    private final ESignNonceMapper nonceMapper;
    private final ObjectMapper objectMapper;

    public ESignCallbackFilter(ESignProperties properties,
                               ESignNonceMapper nonceMapper,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.nonceMapper = nonceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getCallback().isEnabled()) {
            return true;
        }
        return !properties.getCallback().getPath().equals(requestPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String accessKey = properties.getAccessKey();
        String accessSecret = properties.getAccessSecret();
        if (accessKey == null || accessKey.isEmpty() || accessSecret == null || accessSecret.isEmpty()) {
            // fail-closed：密钥未配置时不放任何回调进入
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "电子签章回调密钥未配置，拒绝回调");
            return;
        }

        String key = trimToNull(request.getHeader(HEADER_KEY));
        String timestampText = trimToNull(request.getHeader(HEADER_TIMESTAMP));
        String nonce = trimToNull(request.getHeader(HEADER_NONCE));
        String signature = trimToNull(request.getHeader(HEADER_SIGNATURE));
        if (key == null || timestampText == null || nonce == null || signature == null) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "回调缺少签名头（key/timestamp/nonce/signature）");
            return;
        }
        if (!ESignSignature.constantTimeEquals(accessKey, key)) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "回调 accessKey 不匹配");
            return;
        }
        if (nonce.length() > MAX_NONCE_LEN) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "回调 nonce 非法");
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampText);
        } catch (NumberFormatException e) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "回调时间戳格式非法");
            return;
        }
        // 时间戳取服务端：与本机时钟比对，默认容差 8 分钟，过早（截获旧包）或过晚（时钟漂移/伪造）都拒绝
        long skew = Math.abs(System.currentTimeMillis() - timestamp);
        long maxAgeMs = properties.getCallback().getTimestampMaxAgeSeconds() * 1000L;
        if (skew > maxAgeMs) {
            reject(request, response, HttpStatus.UNAUTHORIZED,
                    "回调已超过 " + properties.getCallback().getTimestampMaxAgeSeconds() + " 秒时效，拒绝（偏移 "
                            + (skew / 1000) + " 秒）");
            return;
        }

        byte[] body = readBody(request, response);
        if (body == null) {
            return; // readBody 已就超限写出响应
        }
        String bodyMd5 = ESignSignature.md5Hex(body);
        String expected = ESignSignature.sign(accessSecret, request.getMethod(),
                properties.getCallback().getPath(), timestampText, nonce, bodyMd5);
        if (!ESignSignature.constantTimeEquals(expected, signature)) {
            // 不回显期望值，只记录（凭 TraceId 可查）；防伪造者据此校准
            log.warn("电子签章回调验签失败 path={} nonce={} bodyMd5={}",
                    properties.getCallback().getPath(), nonce, bodyMd5);
            reject(request, response, HttpStatus.UNAUTHORIZED, "回调签名校验失败");
            return;
        }

        // 验签通过后抢占 nonce：唯一键冲突 = 15 分钟窗口内重放
        LocalDateTime now = LocalDateTime.now();
        ESignNonce record = new ESignNonce();
        record.setNonce(nonce);
        record.setRequestTimestamp(timestamp);
        record.setSeenAt(now);
        record.setExpireAt(now.plusMinutes(properties.getCallback().getNonceWindowMinutes()));
        try {
            nonceMapper.insert(record);
        } catch (DuplicateKeyException e) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "回调 nonce 已在防重放窗口内使用，拒绝重放");
            return;
        }

        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /** 读取请求体原文（验签对象），超限直接拒绝并返回 null。 */
    private byte[] readBody(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(4096, MAX_BODY_BYTES));
        byte[] chunk = new byte[4096];
        int n;
        int total = 0;
        while ((n = request.getInputStream().read(chunk)) != -1) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                reject(request, response, HttpStatus.UNAUTHORIZED, "回调报文超过大小上限");
                return null;
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        HttpStatus status, String message) throws IOException {
        log.warn("电子签章平台回调被拒绝 path={} remote={} reason={}",
                request.getRequestURI(), request.getRemoteAddr(), message);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
    }

    /** 去掉可能的 contextPath，得到与平台签名 PATH 一致的路径。 */
    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
