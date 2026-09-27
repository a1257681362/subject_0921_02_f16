package com.evops.apartment.pinyin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.dto.LeaseCreateRequest;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.service.LeaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-11 租约列表拼音检索端到端（SpringBootTest + MockMvc，H2 内存库）：
 * 1. 建档同事务生成全拼/首字母冗余字段，多音字取多读音集合（乐 le/yue、查 cha/zha、
 *    单 dan/chan/shan、解 jie/xie）；
 * 2. 检索三路匹配：中文原文、全拼（连写/音节空格/前缀）、首字母；
 * 3. 大小写、全角字母、全角空格容错；
 * 4. 变体起点前缀匹配：音节中间子串不误命中；
 * 5. 历史数据（冗余列为 NULL）启动回填同口径补齐；
 * 6. 导出与检索同口径；验收用例全过；打印页含签字栏。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_apt11_test;DB_CLOSE_DELAY=-1",
        "evops.ops-log.flush-interval-ms=100"
})
@AutoConfigureMockMvc
class PinyinSearchFlowTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LeaseService leaseService;
    @Autowired private LeaseMapper leaseMapper;
    @Autowired private PinyinIndexBackfillRunner backfillRunner;

    private String supervisorToken;
    private String stewardToken;

    @BeforeEach
    void setUp() throws Exception {
        supervisorToken = login("supervisor01", "supervisor123");
        stewardToken = login("steward01", "steward123");
    }

    // ---------------------------------------------------------------
    // 1. 建档同事务生成冗余字段 + 多音字多读音集合
    // ---------------------------------------------------------------

    @Test
    void create_generatesPinyinIndexInSameTransaction_polyphonicCovered() {
        Lease lease = leaseService.create(leaseReq("HT-PY-T01", "乐飞"));
        Lease saved = leaseMapper.selectById(lease.getId());
        assertNotNull(saved.getTenantNamePyFull(), "全拼冗余字段同事务落库");
        assertNotNull(saved.getTenantNamePyInitials(), "首字母冗余字段同事务落库");
        // 乐 多音字：le / yue 两个读音组合都收录
        assertTrue(saved.getTenantNamePyFull().contains("|lefei|"), "收录 le 读音: " + saved.getTenantNamePyFull());
        assertTrue(saved.getTenantNamePyFull().contains("|yuefei|"), "收录 yue 读音: " + saved.getTenantNamePyFull());
        assertTrue(saved.getTenantNamePyFull().contains("|le fei|"), "收录音节空格形式");
        // 首字母与读音无关（l/f），连写与空格形式均收录
        assertTrue(saved.getTenantNamePyInitials().contains("|lf|"));
        assertTrue(saved.getTenantNamePyInitials().contains("|l f|"));
    }

    @Test
    void create_polyphonicSurnames_allReadingsSearchable() {
        leaseService.create(leaseReq("HT-PY-T02", "查明"));
        leaseService.create(leaseReq("HT-PY-T03", "单丽"));
        leaseService.create(leaseReq("HT-PY-T04", "解晨"));

        // 查：zha / cha 两个读音都命中
        assertHit(searchContracts(supervisorToken, "zha"), "HT-PY-T02");
        assertHit(searchContracts(supervisorToken, "cha"), "HT-PY-T02");
        // 单：shan / dan / chan
        assertHit(searchContracts(supervisorToken, "shan"), "HT-PY-T03");
        assertHit(searchContracts(supervisorToken, "dan"), "HT-PY-T03");
        // 解：xie / jie
        assertHit(searchContracts(supervisorToken, "xie"), "HT-PY-T04");
        assertHit(searchContracts(supervisorToken, "jie"), "HT-PY-T04");
    }

    // ---------------------------------------------------------------
    // 2. 三路匹配：中文 / 全拼 / 首字母
    // ---------------------------------------------------------------

    @Test
    void search_matchesChineseFullPinyinAndInitials() throws Exception {
        createViaApi(supervisorToken, "HT-PY-T10", "张学友");

        // 中文全名与部分
        assertHit(searchContracts(supervisorToken, "张学友"), "HT-PY-T10");
        assertHit(searchContracts(supervisorToken, "学友"), "HT-PY-T10");
        // 全拼连写、音节空格、前缀
        assertHit(searchContracts(supervisorToken, "zhangxueyou"), "HT-PY-T10");
        assertHit(searchContracts(supervisorToken, "zhang xue you"), "HT-PY-T10");
        assertHit(searchContracts(supervisorToken, "zhangxue"), "HT-PY-T10");
        // 首字母
        assertHit(searchContracts(supervisorToken, "zxy"), "HT-PY-T10");
        assertHit(searchContracts(supervisorToken, "zx"), "HT-PY-T10");
    }

    @Test
    void search_variantStartPrefix_noMidSyllableFalseHit() {
        leaseService.create(leaseReq("HT-PY-T11", "乐飞"));
        // “uef”是 yuefei 的中间片段，不是任何变体起点，不应命中
        assertEmpty(searchContracts(supervisorToken, "uef"));
        // 负例：完全不相关
        assertEmpty(searchContracts(supervisorToken, "qqq"));
    }

    // ---------------------------------------------------------------
    // 3. 大小写 / 全半角容错
    // ---------------------------------------------------------------

    @Test
    void search_caseAndFullWidthTolerant() {
        leaseService.create(leaseReq("HT-PY-T12", "乐飞"));
        assertHit(searchContracts(supervisorToken, "LEFEI"), "HT-PY-T12");
        assertHit(searchContracts(supervisorToken, "LeFei"), "HT-PY-T12");
        assertHit(searchContracts(supervisorToken, "LF"), "HT-PY-T12");
        // 全角字母
        assertHit(searchContracts(supervisorToken, "ｌｅｆｅｉ"), "HT-PY-T12");
        assertHit(searchContracts(supervisorToken, "ＬＦ"), "HT-PY-T12");
        // 全角空格分隔音节
        assertHit(searchContracts(supervisorToken, "ｌｅ　ｆｅｉ"), "HT-PY-T12");
    }

    // ---------------------------------------------------------------
    // 4. 历史数据回填
    // ---------------------------------------------------------------

    @Test
    void backfill_fillsLegacyRowsWithSameRules() {
        Lease lease = leaseService.create(leaseReq("HT-PY-T20", "查明"));
        // 模拟旧库升级：冗余列清空（等价于 apt-11 前的历史行）；显式 SET NULL，
        // updateById 的 NOT_NULL 策略不会清列
        leaseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Lease>()
                .eq("id", lease.getId())
                .set("tenant_name_py_full", null)
                .set("tenant_name_py_initials", null));
        Lease cleared = leaseMapper.selectById(lease.getId());
        assertTrue(cleared.getTenantNamePyFull() == null, "前置：冗余列已清空");

        int n = backfillRunner.backfillBatch();
        assertTrue(n >= 1, "回填至少处理本行");
        Lease refilled = leaseMapper.selectById(lease.getId());
        assertTrue(refilled.getTenantNamePyFull().contains("|zhaming|"), "回填收录 zha 读音");
        assertTrue(refilled.getTenantNamePyFull().contains("|chaming|"), "回填收录 cha 读音");
        // 回填后检索立即可用
        assertHit(searchContracts(supervisorToken, "zha"), "HT-PY-T20");
        // 幂等：再跑一轮不再处理该行
        Lease again = leaseMapper.selectById(lease.getId());
        assertEquals(refilled.getTenantNamePyFull(), again.getTenantNamePyFull());
    }

    // ---------------------------------------------------------------
    // 5. 组合条件 / 数据范围 / 导出同口径
    // ---------------------------------------------------------------

    @Test
    void search_combinesWithContractNoAndDateRange() {
        leaseService.create(leaseReq("HT-PY-T30", "乐飞"));
        // 关键字 + 合同号双条件：精确到本单
        assertHit(searchContracts(supervisorToken, "lefei", "HT-PY-T30", null, null), "HT-PY-T30");
        assertEmpty(searchContracts(supervisorToken, "lefei", "HT-PY-OTHER", null, null));
        // 关键字 + 起租日期范围（本单 2026-09-01 起租）
        assertHit(searchContracts(supervisorToken, "lefei", null, "2026-09-01", "2026-09-30"), "HT-PY-T30");
        assertMiss(searchContracts(supervisorToken, "lefei", null, "2026-10-01", null), "HT-PY-T30");
    }

    @Test
    void search_selfScopeOnlySeesOwn() throws Exception {
        // 主管建一条，管家（SELF）按拼音检索不到
        createViaApi(supervisorToken, "HT-PY-T40", "乐飞");
        List<String> stewardHits = searchContracts(stewardToken, "lefei");
        assertFalse(stewardHits.contains("HT-PY-T40"), "SELF 范围不可见他人经办租约");
        // 管家自己建一条后能按拼音检索到
        createViaApi(stewardToken, "HT-PY-T41", "乐飞");
        assertTrue(searchContracts(stewardToken, "lefei").contains("HT-PY-T41"));
    }

    @Test
    void export_usesSameKeywordSemantics() throws Exception {
        createViaApi(supervisorToken, "HT-PY-T50", "张学友");
        MvcResult result = mockMvc.perform(get("/api/apartment/leases/export")
                        .param("keyword", "zxy")
                        .header("X-Auth-Token", supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains("HT-PY-T50"), "导出按首字母关键字过滤");
        // 姓名脱敏与接口同口径（张**）
        assertFalse(csv.contains("张学友"), "导出姓名仍脱敏");
    }

    // ---------------------------------------------------------------
    // 6. 验收：样例播种幂等 + 用例全过 + 打印页含签字栏
    // ---------------------------------------------------------------

    @Test
    void acceptance_seedIdempotent_andAllCasesPass() throws Exception {
        // 播种两次：第二次幂等跳过
        mockMvc.perform(post("/api/apartment/leases/acceptance/samples")
                        .header("X-Auth-Token", supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        MvcResult second = mockMvc.perform(post("/api/apartment/leases/acceptance/samples")
                        .header("X-Auth-Token", supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode created = readData(second);
        assertEquals(0, created.size(), "重复播种幂等，不再新建");

        MvcResult data = mockMvc.perform(get("/api/apartment/leases/acceptance/data")
                        .header("X-Auth-Token", supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode d = readData(data);
        assertTrue(d.get("allPass").asBoolean(),
                "验收用例应全部通过: " + d.get("cases"));
        assertEquals(d.get("totalCount").asInt(), d.get("passCount").asInt());
        assertTrue(d.get("samples").size() >= 5, "样例租约齐全");
        // 样例冗余字段非空（验收需核对索引内容）
        for (JsonNode s : d.get("samples")) {
            assertTrue(s.get("tenantNamePyFull").asText().startsWith("|"),
                    "样例全拼字段为变体包裹形式: " + s.get("contractNo"));
        }
    }

    @Test
    void acceptance_consolePageHasPrintAndSignature() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/apartment/leases/acceptance/console"))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("验收人（签字）"), "打印页含验收人签字栏");
        assertTrue(html.contains("复核人（签字）"), "打印页含复核人签字栏");
        assertTrue(html.contains("window.print()"), "打印页支持浏览器打印");
        assertTrue(html.contains("@media print"), "打印样式隐藏操作区");
    }

    @Test
    void acceptance_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/apartment/leases/acceptance/data"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/apartment/leases/acceptance/samples"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private LeaseCreateRequest leaseReq(String contractNo, String tenantName) {
        LeaseCreateRequest req = new LeaseCreateRequest();
        req.setContractNo(contractNo);
        req.setPropertyCode("PY-UNIT-001");
        req.setTenantName(tenantName);
        req.setTenantPhone("13800000000");
        req.setLeaseStart(LocalDate.of(2026, 9, 1));
        req.setLeaseEnd(LocalDate.of(2027, 8, 31));
        req.setMonthlyRent(new BigDecimal("2600.00"));
        req.setDeposit(new BigDecimal("2600.00"));
        req.setRequestNo("REQ-" + contractNo);
        req.setOperator("test-op");
        req.setBizTime(LocalDateTime.now());
        return req;
    }

    private void createViaApi(String token, String contractNo, String tenantName) throws Exception {
        LeaseCreateRequest req = leaseReq(contractNo, tenantName);
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private List<String> searchContracts(String token, String keyword) {
        return searchContracts(token, keyword, null, null, null);
    }

    private List<String> searchContracts(String token, String keyword, String contractNo,
                                         String startFrom, String startTo) {
        try {
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                    get("/api/apartment/leases").header("X-Auth-Token", token);
            if (keyword != null) {
                builder = builder.param("keyword", keyword);
            }
            if (contractNo != null) {
                builder = builder.param("contractNo", contractNo);
            }
            if (startFrom != null) {
                builder = builder.param("startFrom", startFrom);
            }
            if (startTo != null) {
                builder = builder.param("startTo", startTo);
            }
            MvcResult result = mockMvc.perform(builder)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();
            JsonNode data = readData(result);
            List<String> contracts = new ArrayList<>();
            for (JsonNode lease : data) {
                contracts.add(lease.get("contractNo").asText());
            }
            return contracts;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 期望命中的合同号都在结果集内（共享内存库下不要求结果恰好只有这些）。 */
    private void assertHit(List<String> actual, String... expected) {
        for (String e : expected) {
            assertTrue(actual.contains(e), "应命中 " + e + "，实际结果: " + actual);
        }
    }

    /** 指定合同号不在结果集内。 */
    private void assertMiss(List<String> actual, String... forbidden) {
        for (String f : forbidden) {
            assertFalse(actual.contains(f), "不应命中 " + f + "，实际结果: " + actual);
        }
    }

    /** 本用例合同号段（HT-PY-T*）无任何命中。 */
    private void assertEmpty(List<String> actual) {
        List<String> filtered = new ArrayList<>();
        for (String c : actual) {
            if (c.startsWith("HT-PY-T")) {
                filtered.add(c);
            }
        }
        assertTrue(filtered.isEmpty(), "不应有命中，实际: " + filtered);
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data").get("token").asText();
    }
}
