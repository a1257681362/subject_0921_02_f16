package com.evops.apartment.mask;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.entity.EmergencyContact;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.esign.ESignClient;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.pinyin.PinyinIndexer;
import com.evops.apartment.task.engine.SignArchiveStepHandler;
import com.evops.apartment.task.entity.AsyncTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-12 嵌套对象递归脱敏端到端（安全抽查缺陷修复验证）：
 * 1. 导出 CSV：扁平字段（证件号）与嵌套对象（紧急联系人姓名/电话）同一套规则；
 * 2. 接口 JSON：嵌套对象成员同样脱敏，非敏感成员（关系）原样；
 * 3. 库内保持明文（脱敏只发生在出口）；
 * 4. 日志出口：签约归档步骤、电签 mock、拼音索引三处不再输出明文姓名；
 * 5. 公寓楼同批租约回归（APT8签约楼 APT8-HT-0001/0002，口径见 docs/apt-12/验证记录.md）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_nested_mask_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SensitiveNestedMaskingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LeaseMapper leaseMapper;
    @Autowired
    private SignArchiveStepHandler stepHandler;
    @Autowired
    private ESignClient eSignClient;

    private String adminToken;

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

    @BeforeEach
    void loginAdmin() throws Exception {
        adminToken = login("admin", "admin123");
    }

    private void createLease(String contractNo, String propertyCode,
                             String start, String end, int rent,
                             String name, String phone, String idCard,
                             String ecName, String ecPhone, String ecRelation) throws Exception {
        String json = "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"" + propertyCode + "\","
                + "\"tenantName\":\"" + name + "\",\"tenantPhone\":\"" + phone + "\","
                + "\"tenantIdCard\":\"" + idCard + "\","
                + "\"emergencyContact\":{\"name\":\"" + ecName + "\",\"phone\":\"" + ecPhone
                + "\",\"relation\":\"" + ecRelation + "\"},"
                + "\"leaseStart\":\"" + start + "\",\"leaseEnd\":\"" + end + "\","
                + "\"monthlyRent\":" + rent + ",\"deposit\":1000,"
                + "\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"op-nested\"}";
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", adminToken)
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isOk());
    }

    private String exportCsv(String contractNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/apartment/leases/export")
                        .header("X-Auth-Token", adminToken)
                        .param("contractNo", contractNo))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 数据行末列（紧急联系人 JSON 单元格）提取并反转义（"" 还原为 "）。 */
    private static String lastCellUnescaped(String csvBody) {
        String[] lines = csvBody.split("\r\n");
        String row = lines[1];
        // 末列单元格以 ,"{"" 起始（JSON 内部的 ,"" 序列不含花括号，不会误判）
        int start = row.lastIndexOf(",\"{\"\"") + 1;
        assertTrue(start > 0 && row.endsWith("\""), "末列应为带引号 JSON 单元格: " + row);
        return row.substring(start + 1, row.length() - 1).replace("\"\"", "\"");
    }

    @Test
    void csvExport_masksNestedEmergencyContact_sameRulesAsFlatFields() throws Exception {
        createLease("HT-NES-0001", "PROP-A-101", "2026-01-01", "2026-12-31", 3000,
                "张三", "13800000000", "110101199001011234", "王五", "13912345678", "配偶");

        String body = exportCsv("HT-NES-0001");

        assertTrue(body.contains("紧急联系人"), "导出应含紧急联系人列: " + body);
        // 扁平字段口径不变：姓名留姓、手机 3+4、证件 6+4
        assertTrue(body.contains("张*,138****0000,110101********1234"),
                "扁平字段脱敏口径应与修复前一致: " + body);
        // 嵌套对象：与扁平字段同一套规则（姓名留姓、手机 3+4），非敏感成员（关系）原样
        assertEquals("{\"name\":\"王*\",\"phone\":\"139****5678\",\"relation\":\"配偶\"}",
                lastCellUnescaped(body), "嵌套对象应按同一套规则递归脱敏");
        // 任何位置不得出现明文
        assertFalse(body.contains("13912345678"), "CSV 不得泄露紧急联系人明文电话");
        assertFalse(body.contains("王五"), "CSV 不得泄露紧急联系人明文姓名");
        assertFalse(body.contains("13800000000"), "CSV 不得泄露租客明文手机号");
        assertFalse(body.contains("110101199001011234"), "CSV 不得泄露明文证件号");
    }

    @Test
    void apiResponse_masksNestedEmergencyContact() throws Exception {
        createLease("HT-NES-0002", "PROP-A-101", "2026-01-01", "2026-12-31", 3000,
                "李四", "13711112222", "31010120050505678X", "赵六", "13622223333", "父母");

        // 检索接口：嵌套对象成员同样脱敏，非敏感成员（关系）原样
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", adminToken)
                        .param("contractNo", "HT-NES-0002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tenantName").value("李*"))
                .andExpect(jsonPath("$.data[0].emergencyContact.name").value("赵*"))
                .andExpect(jsonPath("$.data[0].emergencyContact.phone").value("136****3333"))
                .andExpect(jsonPath("$.data[0].emergencyContact.relation").value("父母"));

        // 库内保持明文：脱敏只发生在出口（与 apt-2 口径一致）
        Lease stored = leaseMapper.selectOne(
                new QueryWrapper<Lease>().eq("contract_no", "HT-NES-0002"));
        assertEquals("李四", stored.getTenantName());
        assertEquals("赵六", stored.getEmergencyContact().getName());
        assertEquals("13622223333", stored.getEmergencyContact().getPhone());
    }

    @Test
    void sensitiveFields_recursesAllNestedShapes() {
        // 实体上的嵌套 bean：readAsString 渲染为脱敏 JSON
        Lease lease = new Lease();
        lease.setTenantName("张三");
        EmergencyContact ec = new EmergencyContact();
        ec.setName("王五");
        ec.setPhone("13912345678");
        ec.setRelation("配偶");
        lease.setEmergencyContact(ec);
        assertEquals("{\"name\":\"王*\",\"phone\":\"139****5678\",\"relation\":\"配偶\"}",
                SensitiveFields.readAsString(lease, "emergencyContact"));

        // 集合 / Map / 数组中的 bean 同样递归
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("contacts", Arrays.asList(ec, ec));
        Map<String, Object> nested = new HashMap<>();
        nested.put("backup", ec);
        graph.put("nested", nested);
        graph.put("arr", new Object[]{ec});
        String json = SensitiveFields.toMaskedJson(graph);
        assertFalse(json.contains("13912345678"), "集合/Map/数组内不得泄露明文: " + json);
        assertFalse(json.contains("王五"), "集合/Map/数组内不得泄露明文姓名: " + json);
        assertTrue(json.contains("139****5678"), "集合/Map/数组内应按规则脱敏: " + json);

        // 循环引用：截断为 null，不栈溢出
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        a.put("b", b);
        b.put("a", a);
        b.put("contact", ec);
        String cyclic = SensitiveFields.toMaskedJson(a);
        assertTrue(cyclic.contains("139****5678"), "循环引用前的节点仍应脱敏: " + cyclic);

        // @JsonIgnore 字段不输出（与接口口径一致）；整对象渲染同样脱敏
        lease.setTenantNamePyFull("|zhangsan|");
        String leaseJson = SensitiveFields.toMaskedJson(lease);
        assertFalse(leaseJson.contains("tenantNamePyFull"), "@JsonIgnore 字段不应出现在日志渲染中");
        assertTrue(leaseJson.contains("\"tenantName\":\"张*\""), "整对象渲染应脱敏姓名: " + leaseJson);
        assertTrue(leaseJson.contains("\"phone\":\"139****5678\""), "整对象渲染应递归脱敏嵌套成员: " + leaseJson);
    }

    @Test
    void logs_useSameMaskingRules() {
        // 1. 签约归档步骤 10（签署链接送达）：租客姓名不得明文入日志
        Logger stepLogger = (Logger) LoggerFactory.getLogger(SignArchiveStepHandler.class);
        ListAppender<ILoggingEvent> stepEvents = new ListAppender<>();
        stepEvents.start();
        stepLogger.addAppender(stepEvents);
        try {
            AsyncTask task = new AsyncTask();
            task.setTaskNo("TASK-LOG-0001");
            task.setPayload("{\"contractNo\":\"HT-LOG-0001\",\"propertyCode\":\"PROP-A-101\","
                    + "\"tenantName\":\"张三\",\"tenantPhone\":\"13800000000\","
                    + "\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                    + "\"monthlyRent\":3000,\"deposit\":1000}");
            task.setCheckpoint("{\"bizNo\":\"EVOPS-HT-LOG-0001\",\"signUrl\":\"/demo/sign.html?flowNo=F1\"}");
            stepHandler.executeStep(task, 10);
        } finally {
            stepLogger.detachAppender(stepEvents);
        }
        String stepLogs = joinMessages(stepEvents);
        assertTrue(stepLogs.contains("签署链接已送达租客"), "应产生送达日志: " + stepLogs);
        assertFalse(stepLogs.contains("张三"), "日志不得泄露租客明文姓名: " + stepLogs);
        assertTrue(stepLogs.contains("张*"), "日志应按同一套规则脱敏: " + stepLogs);

        // 2. 电签 mock 发起签署：签署人姓名不得明文入日志
        Logger esignLogger = (Logger) LoggerFactory.getLogger(
                com.evops.apartment.esign.MockESignPlatformClient.class);
        ListAppender<ILoggingEvent> esignEvents = new ListAppender<>();
        esignEvents.start();
        esignLogger.addAppender(esignEvents);
        try {
            String flowNo = eSignClient.createSealRequest("EVOPS-HT-LOG-0002", "租赁合同-HT-LOG-0002");
            String docToken = eSignClient.createDocument(flowNo, "EVOPS-HT-LOG-0002", "合同", "内容");
            eSignClient.startTenantSign(flowNo, "EVOPS-HT-LOG-0002", docToken, "张三丰", "110101199001011234");
        } finally {
            esignLogger.detachAppender(esignEvents);
        }
        String esignLogs = joinMessages(esignEvents);
        assertTrue(esignLogs.contains("startTenantSign"), "应产生发起签署日志: " + esignLogs);
        assertFalse(esignLogs.contains("张三丰"), "日志不得泄露签署人明文姓名: " + esignLogs);
        assertTrue(esignLogs.contains("张**"), "日志应按同一套规则脱敏: " + esignLogs);

        // 3. 拼音索引截断告警：姓名不得明文入日志
        Logger pinyinLogger = (Logger) LoggerFactory.getLogger(PinyinIndexer.class);
        ListAppender<ILoggingEvent> pinyinEvents = new ListAppender<>();
        pinyinEvents.start();
        pinyinLogger.addAppender(pinyinEvents);
        try {
            // 8 个多音字（乐：le/yue）组合 256 > MAX_VARIANTS(128)，触发截断告警
            PinyinIndexer.build("乐乐乐乐乐乐乐乐");
        } finally {
            pinyinLogger.detachAppender(pinyinEvents);
        }
        String pinyinLogs = joinMessages(pinyinEvents);
        assertFalse(pinyinLogs.isEmpty(), "应触发拼音截断告警日志");
        assertFalse(pinyinLogs.contains("乐乐乐乐乐乐乐乐"), "日志不得泄露明文姓名: " + pinyinLogs);
        assertTrue(pinyinLogs.contains("乐*******"), "日志应按同一套规则脱敏: " + pinyinLogs);
    }

    @Test
    void regression_apt8BuildingBatch_allRowsMasked() throws Exception {
        // 公寓楼同批租约（APT8签约楼，与归档报告 target/apt8-archive 同口径）+ 紧急联系人
        createLease("APT8-HT-0001", "APT8-P-001", "2026-01-01", "2026-12-31", 3000,
                "李雷", "13811110001", "110101199001010001", "韩梅", "13911110001", "配偶");
        createLease("APT8-HT-0002", "APT8-P-002", "2026-02-01", "2027-01-31", 3200,
                "李梅", "13811110002", "110101199001010002", "李国强", "13911110002", "父母");

        // 逐合同导出：两条均全字段脱敏，任何明文不得出现
        for (String contractNo : Arrays.asList("APT8-HT-0001", "APT8-HT-0002")) {
            String csv = exportCsv(contractNo);
            assertTrue(csv.contains("紧急联系人"), "导出应含紧急联系人列: " + csv);
            assertFalse(csv.contains("1381111000"), "不得泄露租客明文手机号: " + csv);
            assertFalse(csv.contains("1101011990010"), "不得泄露明文证件号: " + csv);
            assertFalse(csv.contains("1391111000"), "不得泄露紧急联系人明文电话: " + csv);
            assertFalse(csv.contains("韩梅"), "不得泄露紧急联系人明文姓名: " + csv);
            assertFalse(csv.contains("李国强"), "不得泄露紧急联系人明文姓名: " + csv);
        }
        String csv1 = exportCsv("APT8-HT-0001");
        assertTrue(csv1.contains("李*,138****0001,110101********0001"), "扁平字段同口径: " + csv1);
        assertEquals("{\"name\":\"韩*\",\"phone\":\"139****0001\",\"relation\":\"配偶\"}",
                lastCellUnescaped(csv1), "同批租约紧急联系人按同一套规则脱敏");

        // 接口同批核验
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", adminToken)
                        .param("contractNo", "APT8-HT-0002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tenantName").value("李*"))
                .andExpect(jsonPath("$.data[0].emergencyContact.name").value("李**"))
                .andExpect(jsonPath("$.data[0].emergencyContact.phone").value("139****0002"));
    }

    private static String joinMessages(ListAppender<ILoggingEvent> appender) {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
