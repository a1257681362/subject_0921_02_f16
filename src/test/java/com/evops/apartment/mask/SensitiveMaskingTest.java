package com.evops.apartment.mask;

import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.LeaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 脱敏端到端：
 * 1. 接口 JSON 脱敏、库里保持明文；
 * 2. 租约 CSV 导出与接口同一字段同一口径；
 * 3. 规则文件热加载：真实文件事件、坏文件保留旧规则、删除回退默认、总开关。
 * 规则文件指向临时目录，避免碰交付的 config/sensitive-rules.yml。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_mask_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SensitiveMaskingTest {
    @TempDir
    static Path tempDir;
    static Path ruleFile;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LeaseMapper leaseMapper;
    @Autowired
    private SensitiveRuleRegistry registry;

    private String adminToken;

    /** apt-3 起公寓接口需登录：管理员范围 ALL，不影响脱敏断言。 */
    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"token\":\"");
        return body.substring(idx + 9, body.indexOf("\"", idx + 9));
    }

    @DynamicPropertySource
    static void ruleFilePath(DynamicPropertyRegistry reg) {
        // lambda 在 Spring 上下文首次加载时执行，晚于 @BeforeAll，故用 tempDir 现取
        reg.add("evops.sensitive.rule-file", () -> tempDir.resolve("sensitive-rules.yml").toString());
    }

    @BeforeAll
    static void sanity() {
        ruleFile = tempDir.resolve("sensitive-rules.yml");
        // 临时规则文件初始不存在：应直接用 application.yml/内置默认
        assertFalse(Files.exists(ruleFile));
    }

    @BeforeEach
    void resetRules() throws Exception {
        adminToken = login("admin", "admin123");
        // 类内共享上下文：每个用例前清掉规则文件并等待回退默认，杜绝用例间规则串味
        Files.deleteIfExists(ruleFile);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(6));
        while (Instant.now().isBefore(deadline)
                && !"138****0000".equals(registry.mask(SensitiveType.PHONE, "13800000000"))) {
            Thread.sleep(100);
        }
        assertEquals("138****0000", registry.mask(SensitiveType.PHONE, "13800000000"),
                "用例前置：规则未回退默认，热加载可能异常");
    }

    @AfterAll
    static void restoreDefaultRules() throws Exception {
        // 热加载用例会改全局规则；类内共享上下文，结束后强制回退默认，避免污染后续测试
        Files.deleteIfExists(ruleFile);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(6));
        while (Instant.now().isBefore(deadline)
                && !"138****0000".equals(SensitiveRuleRegistry.getInstance()
                        .mask(SensitiveType.PHONE, "13800000000"))) {
            Thread.sleep(100);
        }
    }

    private String createLeaseJson(String contractNo, String name, String phone, String idCard) {
        String idCardPart = idCard == null ? "" : ",\"tenantIdCard\":\"" + idCard + "\"";
        return "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"PROP-A-101\","
                + "\"tenantName\":\"" + name + "\",\"tenantPhone\":\"" + phone + "\""
                + idCardPart
                + ",\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                + "\"monthlyRent\":3000,\"deposit\":1000,"
                + "\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"op-mask\"}";
    }

    @Test
    void apiResponse_isMasked_butDatabaseStaysPlaintext() throws Exception {
        // 建档返回体本身也要脱敏
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", adminToken)
                        .contentType("application/json")
                        .content(createLeaseJson("HT-MASK-0001", "张三", "13800000000", "110101199001011234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantName").value("张*"))
                .andExpect(jsonPath("$.data.tenantPhone").value("138****0000"))
                .andExpect(jsonPath("$.data.tenantIdCard").value("110101********1234"));

        // 检索接口同样脱敏
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", adminToken)
                        .param("contractNo", "HT-MASK-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tenantName").value("张*"))
                .andExpect(jsonPath("$.data[0].tenantPhone").value("138****0000"))
                .andExpect(jsonPath("$.data[0].tenantIdCard").value("110101********1234"));

        // 单字姓名：保留位覆盖全串时整体遮蔽
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", adminToken)
                        .contentType("application/json")
                        .content(createLeaseJson("HT-MASK-0002", "李", "13900000000", null)))
                .andExpect(jsonPath("$.data.tenantName").value("*"))
                .andExpect(jsonPath("$.data.tenantPhone").value("139****0000"));

        // 库里仍是明文，脱敏只发生在出口
        Lease stored = leaseMapper.selectOne(new QueryWrapper<Lease>().eq("contract_no", "HT-MASK-0001"));
        assertEquals("张三", stored.getTenantName());
        assertEquals("13800000000", stored.getTenantPhone());
        assertEquals("110101199001011234", stored.getTenantIdCard());
    }

    @Test
    void csvExport_usesSameRulesAsApi() throws Exception {
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", adminToken)
                        .contentType("application/json")
                        .content(createLeaseJson("HT-CSV-0001", "欧阳娜娜", "13711112222", "31010120050505678X")));

        MvcResult result = mockMvc.perform(get("/api/apartment/leases/export")
                        .header("X-Auth-Token", adminToken)
                        .param("contractNo", "HT-CSV-0001"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.charAt(0) == 0xFEFF, "CSV 应带 UTF-8 BOM");
        assertTrue(body.contains("租客姓名,手机号,证件号"), "应含中文表头: " + body);
        // 与接口口径一致：姓名留姓、手机 3+4、证件 6+4
        assertTrue(body.contains("欧***,137****2222,310101********678X"),
                "CSV 脱敏值应与接口一致，实际: " + body);
        assertFalse(body.contains("13711112222"), "CSV 不得泄露明文手机号");
        assertFalse(body.contains("欧阳娜娜"), "CSV 不得泄露明文姓名");
    }

    @Test
    void ruleFileChange_isHotReloaded() throws Exception {
        // 写入外部文件：手机改留前 2 后 2，等待 WatchService 事件 + 去抖后生效
        writeRuleFile(
                "evops:",
                "  sensitive:",
                "    rules:",
                "      phone: { keep-prefix: 2, keep-suffix: 2, mask-char: \"*\" }");
        assertTrue(awaitMask(SensitiveType.PHONE, "13800000000", "13*******00", Duration.ofSeconds(8)),
                "规则文件变更应在数秒内热加载");
        // 未在文件中出现的类型沿用默认底
        assertEquals("张*", registry.mask(SensitiveType.NAME, "张三"));

        // 坏文件：解析失败保留旧规则
        writeRuleFile("evops:\n  sensitive:\n    rules: [this-is-not-a-map");
        Thread.sleep(1500);
        assertEquals("13*******00", registry.mask(SensitiveType.PHONE, "13800000000"),
                "坏规则文件不得影响在用规则");

        // 总开关：enabled=false 返回明文（接口与导出同源，验注册表即可）
        writeRuleFile("evops:", "  sensitive:", "    enabled: false");
        assertTrue(awaitMask(SensitiveType.PHONE, "13800000000", "13800000000", Duration.ofSeconds(8)),
                "关闭脱敏后应返回明文");

        // 删除文件：回退启动默认
        Files.deleteIfExists(ruleFile);
        assertTrue(awaitMask(SensitiveType.PHONE, "13800000000", "138****0000", Duration.ofSeconds(8)),
                "删除规则文件后应回退默认规则");
    }

    private void writeRuleFile(String... lines) throws IOException {
        Files.write(ruleFile, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private boolean awaitMask(SensitiveType type, String input, String expected, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (expected.equals(registry.mask(type, input))) {
                return true;
            }
            Thread.sleep(100);
        }
        return expected.equals(registry.mask(type, input));
    }
}
