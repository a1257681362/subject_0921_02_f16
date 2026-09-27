package com.evops.apartment.sensitive;

import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.HandoverRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.mapper.LeaseMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-4 端到端：
 * 1. 交接班登记：打码级命中自动打码落库，痕迹可定位到账号/字段/字位；
 * 2. 拦截级（含分隔符插空、变形）拒绝写入，业务回滚但痕迹独立事务保留；
 * 3. 接班人确认与状态机、禁止自交自接；
 * 4. 租约备注/退租备注同样过滤；
 * 5. 词典后台维护即时生效（新增即拦截、停用即放行），维护留痕可查；
 * 6. 权限：管家无词库管理/痕迹查看权（403），主管可看痕迹不可改词库。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_apt4_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SensitiveFlowTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SensitiveAuditMapper auditMapper;
    @Autowired
    private HandoverRecordMapper handoverMapper;
    @Autowired
    private LeaseMapper leaseMapper;

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"token\":\"");
        assertNotNull(body);
        return body.substring(idx + 9, body.indexOf("\"", idx + 9));
    }

    private String handoverJson(String community, String building, String pending, String content) {
        String pendingPart = pending == null ? "" : ",\"pendingItems\":\"" + pending + "\"";
        return "{\"community\":\"" + community + "\",\"building\":\"" + building + "\","
                + "\"shift\":\"MORNING\",\"shiftDate\":\"2026-09-23\"" + pendingPart
                + ",\"keysCardCount\":3,\"cashAmount\":200.00,\"content\":\"" + content + "\","
                + "\"requestNo\":\"REQ-HO-" + System.nanoTime() + "\",\"operator\":\"ignored\"}";
    }

    private String leaseJson(String contractNo, String remark) {
        String remarkPart = remark == null ? "" : ",\"remark\":\"" + remark + "\"";
        return "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"PROP-A-101\","
                + "\"tenantName\":\"张三\",\"tenantPhone\":\"13800000000\","
                + "\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                + "\"monthlyRent\":3000,\"deposit\":1000" + remarkPart
                + ",\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"ignored\"}";
    }

    @Test
    @Order(1)
    void handover_maskLevel_masksAndAuditsWithLocation() throws Exception {
        String steward = login("steward01", "steward123");
        long before = handoverMapper.selectCount(null);

        // “加微信”为打码级（种子词 id=3）：内容里命中后打码放行
        MvcResult result = mockMvc.perform(post("/api/apartment/handovers")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(handoverJson("阳光小区", "2栋", null, "请加微信联系看房")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.operator").value("steward01"))
                .andExpect(jsonPath("$.data.content").value("请***联系看房"))
                .andReturn();
        long after = handoverMapper.selectCount(null);
        assertEquals(before + 1, after, "打码级命中应正常落库 1 条");

        // 命中痕迹：定位到账号、字段、字位（“加微信”在原文 [1,4)）
        String body = result.getResponse().getContentAsString();
        Long id = Long.valueOf(body.substring(body.indexOf("\"id\":") + 5,
                body.indexOf(",", body.indexOf("\"id\":"))));
        SensitiveAudit audit = auditMapper.selectOne(new QueryWrapper<SensitiveAudit>()
                .eq("module", "HANDOVER").eq("field_path", "content")
                .eq("action_taken", "MASK").orderByDesc("id").last("LIMIT 1"));
        assertNotNull(audit);
        assertEquals("steward01", audit.getUsername());
        assertEquals("加微信", audit.getWordText());
        assertEquals(1, audit.getStartIndex());
        assertEquals(4, audit.getEndIndex());
        assertEquals("阳光小区/2栋/2026-09-23", audit.getBizRef());
        assertTrue(audit.getMaskedText().contains("***"));

        // 详情可查（管家本人交班，SELF 范围放行）
        mockMvc.perform(get("/api/apartment/handovers/" + id).header("X-Auth-Token", steward))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("请***联系看房"));
    }

    @Test
    @Order(2)
    void handover_blockLevel_rollsBackButKeepsAuditTrail() throws Exception {
        String steward = login("steward01", "steward123");
        long before = handoverMapper.selectCount(null);

        // “代开发票”为拦截级（种子词 id=1），且用分隔符插空：代-开-发-票
        mockMvc.perform(post("/api/apartment/handovers")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(handoverJson("阳光小区", "3栋", "代-开-发-票私聊", "正常交接内容")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("pendingItems")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("代开发票")));

        long after = handoverMapper.selectCount(null);
        assertEquals(before, after, "拦截级命中不得落库");

        // 痕迹仍保留（REQUIRES_NEW 独立事务），字位覆盖插空区间 [0,7)
        SensitiveAudit audit = auditMapper.selectOne(new QueryWrapper<SensitiveAudit>()
                .eq("module", "HANDOVER").eq("field_path", "pendingItems")
                .eq("action_taken", "BLOCK").orderByDesc("id").last("LIMIT 1"));
        assertNotNull(audit, "业务回滚后拦截痕迹必须保留");
        assertEquals("steward01", audit.getUsername());
        assertEquals(0, audit.getStartIndex());
        assertEquals(7, audit.getEndIndex());
        assertTrue(audit.getRawExcerpt().contains("代-开-发-票"));
    }

    @Test
    @Order(3)
    void propertyAddressAndLeaseRemark_andCheckout_areFiltered() throws Exception {
        String admin = login("admin", "admin123");
        String steward = login("steward01", "steward123");

        // 台账地址备注里塞拦截词（变形：全角/符号插空），管理员登记被拒
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"propertyCode\":\"PROP-X-1\",\"community\":\"示例小区\","
                                + "\"building\":\"1栋\",\"roomNo\":\"101\",\"area\":45.5,"
                                + "\"remark\":\"可代·开·发·票\","
                                + "\"requestNo\":\"REQ-PROP-X\",\"operator\":\"ignored\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("remark")));

        // 租约备注命中打码级“有房出租”：打码落库
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(leaseJson("HT-A4-0001", "房东直租有房出租无中介")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remark").value("房东直租****无中介"));
        Lease lease = leaseMapper.selectOne(new QueryWrapper<Lease>().eq("contract_no", "HT-A4-0001"));
        assertEquals("房东直租****无中介", lease.getRemark());

        // 退租验房备注命中打码级“免押金”：同事务打码落库
        mockMvc.perform(post("/api/apartment/leases/checkout")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"contractNo\":\"HT-A4-0001\",\"checkoutDate\":\"2026-09-23\","
                                + "\"inspectionResult\":\"PASS\",\"inspectionRemark\":\"墙面免押金处理\","
                                + "\"requestNo\":\"REQ-CK-A4\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inspectionRemark").value("墙面***处理"));
    }

    @Test
    @Order(4)
    void handoverConfirm_stateMachineAndNoSelfConfirm() throws Exception {
        String steward = login("steward01", "steward123");
        String admin = login("admin", "admin123");

        MvcResult created = mockMvc.perform(post("/api/apartment/handovers")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(handoverJson("阳光小区", "5栋", null, "夜间无异常")))
                .andExpect(status().isOk())
                .andReturn();
        long id = parseId(created.getResponse().getContentAsString());

        // 自交自接拒绝
        mockMvc.perform(post("/api/apartment/handovers/" + id + "/confirm")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"requestNo\":\"REQ-CF-1\",\"operator\":\"ignored\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("交班人与接班人不能为同一人"));

        // 另一人确认成功
        mockMvc.perform(post("/api/apartment/handovers/" + id + "/confirm")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"requestNo\":\"REQ-CF-2\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.takeOverBy").value("admin"));

        // 重复确认拒绝
        mockMvc.perform(post("/api/apartment/handovers/" + id + "/confirm")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"requestNo\":\"REQ-CF-3\",\"operator\":\"ignored\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("该交接班记录已确认，不能重复确认"));

        // 检索：管家 SELF 可见本人交班/接班记录
        mockMvc.perform(get("/api/apartment/handovers").header("X-Auth-Token", steward)
                        .param("community", "阳光小区").param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].takeOverBy").value("admin"));
    }

    @Test
    @Order(5)
    void dictionaryAdmin_takesEffectImmediately_andLeavesOpLogs() throws Exception {
        String admin = login("admin", "admin123");
        String steward = login("steward01", "steward123");

        // 管家没有词库管理权限
        mockMvc.perform(get("/api/apartment/sensitive/words").header("X-Auth-Token", steward))
                .andExpect(status().isForbidden());
        // 主管有痕迹查看权但无词库管理权
        String supervisor = login("supervisor01", "supervisor123");
        mockMvc.perform(get("/api/apartment/sensitive/audits").header("X-Auth-Token", supervisor))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/apartment/sensitive/words")
                        .header("X-Auth-Token", supervisor)
                        .contentType("application/json")
                        .content("{\"wordText\":\"x\",\"category\":\"AD\",\"actionLevel\":2}"))
                .andExpect(status().isForbidden());

        // 管理员新增拦截词，立即生效
        mockMvc.perform(post("/api/apartment/sensitive/words")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"wordText\":\"转租赚差价\",\"category\":\"AD\",\"actionLevel\":2,"
                                + "\"remark\":\"apt-4 测试新增\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists());
        mockMvc.perform(post("/api/apartment/handovers")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(handoverJson("阳光小区", "6栋", null, "可转租赚差价")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("转租赚差价")));

        // 查出该词并停用，立即放行
        MvcResult words = mockMvc.perform(get("/api/apartment/sensitive/words")
                        .header("X-Auth-Token", admin).param("keyword", "转租赚差价"))
                .andExpect(status().isOk()).andReturn();
        long wordId = parseId(words.getResponse().getContentAsString());
        mockMvc.perform(post("/api/apartment/sensitive/words/" + wordId + "/status")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        mockMvc.perform(post("/api/apartment/handovers")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(handoverJson("阳光小区", "6栋", null, "可转租赚差价")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("可转租赚差价"));

        // 维护留痕：CREATE + DISABLE 各一条，操作人=admin
        mockMvc.perform(get("/api/apartment/sensitive/word-logs")
                        .header("X-Auth-Token", admin).param("wordId", String.valueOf(wordId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("DISABLE"))
                .andExpect(jsonPath("$.data[0].operator").value("admin"))
                .andExpect(jsonPath("$.data[1].action").value("CREATE"))
                .andExpect(jsonPath("$.data[1].operator").value("admin"));
    }

    @Test
    @Order(6)
    void auditTrail_isQueryable_locatesPersonFieldAndPosition() throws Exception {
        String admin = login("admin", "admin123");
        String steward = login("steward01", "steward123");

        // 管家无痕迹查看权
        mockMvc.perform(get("/api/apartment/sensitive/audits").header("X-Auth-Token", steward))
                .andExpect(status().isForbidden());

        // 管理员按动作+模块检索拦截痕迹，能定位到 steward01 的插空提交
        mockMvc.perform(get("/api/apartment/sensitive/audits").header("X-Auth-Token", admin)
                        .param("actionTaken", "BLOCK")
                        .param("module", "HANDOVER")
                        .param("username", "steward01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("steward01"))
                .andExpect(jsonPath("$.data[0].fieldPath").exists())
                .andExpect(jsonPath("$.data[0].startIndex").exists())
                .andExpect(jsonPath("$.data[0].requestNo").exists());
    }

    private long parseId(String body) {
        int idx = body.indexOf("\"id\":");
        return Long.parseLong(body.substring(idx + 5, body.indexOf(",", idx)));
    }
}
