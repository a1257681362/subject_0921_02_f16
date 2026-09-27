package com.evops.apartment.esign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 真实电子签章平台网关客户端：{@code evops.esign.provider=http} 时启用。
 *
 * - 纯 JDK HttpURLConnection，不引新依赖；
 * - 每次请求带 X-EV-Key / X-EV-Timestamp / X-EV-Nonce / X-EV-Signature（HMAC-SHA256，
 *   签名串 = METHOD\nPATH\ntimestamp\nnonce\nbodyMd5），平台侧按同算法验签防重放；
 * - 所有写接口以 bizNo 为业务幂等号放在请求体里，平台侧去重，断点重跑不产生重复单据；
 * - 不在客户端做自动重试：超时/失败一律抛 {@link ESignException}，由任务引擎按断点重跑，
 *   避免“平台已受理但客户端超时”时重复提交（幂等号兜底，重跑安全）。
 *
 * 下周对接：拿到平台地址/密钥后只改 evops.esign.* 配置；若真实平台报文口径不同，
 * 仅在本类内调整 URL 与字段映射，任务步骤与上层接口不动。
 */
@Component
@ConditionalOnProperty(name = "evops.esign.provider", havingValue = "http")
public class HttpESignPlatformClient implements ESignClient {
    private static final Logger log = LoggerFactory.getLogger(HttpESignPlatformClient.class);

    private final ESignProperties properties;
    private final ObjectMapper objectMapper;

    public HttpESignPlatformClient(ESignProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void ping() {
        invoke("GET", "/v1/ping", null);
    }

    @Override
    public String createSealRequest(String bizNo, String subject) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("bizNo", bizNo);
        body.put("subject", subject);
        return invoke("POST", "/v1/seal-requests", body).path("flowNo").asText();
    }

    @Override
    public String createDocument(String flowNo, String bizNo, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowNo", flowNo);
        body.put("bizNo", bizNo);
        body.put("title", title);
        body.put("content", content);
        return invoke("POST", "/v1/documents", body).path("docToken").asText();
    }

    @Override
    public String startTenantSign(String flowNo, String bizNo, String docToken,
                                  String signerName, String signerIdCard) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("flowNo", flowNo);
        body.put("bizNo", bizNo);
        body.put("docToken", docToken);
        body.put("signerName", signerName);
        body.put("signerIdCard", signerIdCard);
        return invoke("POST", "/v1/signs/start", body).path("signUrl").asText();
    }

    @Override
    public String querySignStatus(String flowNo) {
        return invoke("GET", "/v1/signs/" + flowNo + "/status", null).path("status").asText();
    }

    @Override
    public String queryFlowStatus(String flowNo) {
        return invoke("GET", "/v1/flows/" + flowNo, null).path("status").asText();
    }

    @Override
    public void seal(String flowNo, String bizNo) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("flowNo", flowNo);
        body.put("bizNo", bizNo);
        invoke("POST", "/v1/seals", body);
    }

    @Override
    public SignedFile fetchSignedFile(String flowNo, String bizNo, String docToken) {
        String query = "?flowNo=" + enc(flowNo) + "&bizNo=" + enc(bizNo) + "&docToken=" + enc(docToken);
        JsonNode node = invoke("GET", "/v1/files" + query, null);
        String fileName = node.path("fileName").asText();
        // 文件走 Base64 传输，避免二进制/换行破坏 JSON
        String content = new String(Base64.getDecoder().decode(node.path("contentBase64").asText()),
                StandardCharsets.UTF_8);
        return new SignedFile(fileName, content);
    }

    // ---------------- HTTP + 签名 ----------------

    private JsonNode invoke(String method, String path, Map<String, ?> body) {
        String bodyText = body == null ? "" : writeJson(body);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = sign(method, path, timestamp, nonce, bodyText);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(trimSlash(properties.getBaseUrl()) + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(properties.getConnectTimeoutMs());
            conn.setReadTimeout(properties.getReadTimeoutMs());
            conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-EV-Key", properties.getAccessKey());
            conn.setRequestProperty("X-EV-Timestamp", timestamp);
            conn.setRequestProperty("X-EV-Nonce", nonce);
            conn.setRequestProperty("X-EV-Signature", signature);
            if (!bodyText.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyText.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            String resp = readBody(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status < 200 || status >= 300) {
                throw new ESignException("电子签章平台返回 " + status + ": " + truncate(resp));
            }
            if (resp.isEmpty()) {
                return objectMapper.createObjectNode();
            }
            JsonNode node = objectMapper.readTree(resp);
            if (node.path("code").asInt(0) != 0 && node.has("code")) {
                throw new ESignException("电子签章平台业务失败: " + node.path("message").asText());
            }
            return node.path("data").isMissingNode() ? node : node.path("data");
        } catch (ESignException e) {
            throw e;
        } catch (IOException e) {
            throw new ESignException("调用电子签章平台失败 [" + method + " " + path + "]: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 签名串：METHOD\nPATH\ntimestamp\nnonce\nbodyMd5（GET 无 body 时 md5 空串）。
     * 与平台约定一致；nonce 由平台防重放窗口内去重。算法与入站回调验签共用 {@link ESignSignature}。
     */
    private String sign(String method, String path, String timestamp, String nonce, String bodyText) {
        return ESignSignature.sign(properties.getAccessSecret(), method, path,
                timestamp, nonce, ESignSignature.md5Hex(bodyText));
    }

    private String writeJson(Map<String, ?> body) {
        try {
            return objectMapper.writeValueAsString(body == null ? Collections.emptyMap() : body);
        } catch (Exception e) {
            throw new ESignException("电子签章请求序列化失败", e);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String trimSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() <= 500 ? s : s.substring(0, 500));
    }
}
