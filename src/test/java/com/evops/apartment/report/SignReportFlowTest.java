package com.evops.apartment.report;

import com.evops.apartment.report.entity.SignReportVersion;
import com.evops.apartment.report.mapper.SignReportVersionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-8 签约结果 Word 报告端到端：
 * 1. poi-tl：页眉带公寓楼名、租约明细循环成行、租金合计自动汇总；
 * 2. 版本登记 +1、文件落归档目录，历史报告不被覆盖（两份文件都在、内容不同）；
 * 3. 明细为空时合计行为 0 且不报错；
 * 4. demo01 演示账号权限只开本期范围：/me 仅报告菜单与 3 个按钮，访问台账/租约等 403；
 * 5. 数据范围：SCOPE 限示例小区，跨片区生成 403、列表不可见；匿名 401。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_apt8_test;DB_CLOSE_DELAY=-1",
        "evops.sign-report.archive-dir=target/apt8-archive"
})
@AutoConfigureMockMvc
class SignReportFlowTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SignReportVersionMapper versionMapper;

    private String demoToken;
    private String supervisorToken;
    private String stewardToken;

    @BeforeEach
    void setUp() throws Exception {
        demoToken = login("demo01", "demo123");
        supervisorToken = login("supervisor01", "supervisor123");
        stewardToken = login("steward01", "steward123");
    }

    @Test
    void demoAccount_scopedToReportOnly() throws Exception {
        // /me：演示账号只挂 report_demo，菜单仅报告菜单，按钮恰为生成/查看（+菜单共 3 权限点）
        MvcResult me = mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", demoToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode user = readNode(me).path("data");
        assertEquals("[\"report_demo\"]", user.path("roles").toString());
        List<String> menus = toStringList(user.path("menus"));
        List<String> buttons = toStringList(user.path("buttons"));
        assertTrue(menus.contains("apartment:menu:sign-report"));
        assertEquals(1, menus.size(), "演示账号只应有签约报告一个菜单: " + menus);
        assertTrue(buttons.contains("apartment:sign-report:generate"));
        assertTrue(buttons.contains("apartment:sign-report:view"));
        assertEquals(2, buttons.size(), "演示账号只应有生成/查看两个按钮: " + buttons);
        assertEquals("SCOPE", user.path("dataScope").path("level").asText());
        String communitiesJson = user.path("dataScope").path("communities").toString();
        assertTrue(communitiesJson.contains("示例小区"), "SCOPE 应含示例小区: " + communitiesJson);

        // 越权访问既有功能一律 403：台账登记、租约建档、租约检索、签约任务列表
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", demoToken).contentType("application/json")
                        .content("{\"propertyCode\":\"X\",\"community\":\"示例小区\",\"building\":\"b\","
                                + "\"requestNo\":\"r1\",\"operator\":\"ignored\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", demoToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/apartment/sign-archive/tasks").header("X-Auth-Token", demoToken))
                .andExpect(status().isForbidden());
        // 未登录 401
        mockMvc.perform(post("/api/apartment/sign-reports/generate").contentType("application/json")
                .content(generateJson("任意楼", "示例小区"))).andExpect(status().isUnauthorized());
    }

    @Test
    void generate_wordHasHeaderLoopRowsAndSum_versionsKeptAndNotOverwritten() throws Exception {
        String building = "APT8签约楼";
        // 主管登记两套房源（同一栋楼），管家各建一签租约，月租金 3000 + 3200 = 6200
        registerLedger(supervisorToken, "APT8-P-001", "示例小区", building, "101");
        registerLedger(supervisorToken, "APT8-P-002", "示例小区", building, "102");
        createLease(stewardToken, "APT8-HT-0001", "APT8-P-001", "2026-01-01", "2026-12-31", 3000);
        createLease(stewardToken, "APT8-HT-0002", "APT8-P-002", "2026-02-01", "2027-01-31", 3200);

        // 第一版
        long v1 = generateAndExpectVersion(demoToken, building, "示例小区", 1, 2, 6200.00);
        byte[] docx1 = download(demoToken, v1);
        WordText word1 = extractWord(docx1);
        assertTrue(word1.header.contains(building), "页眉应带公寓楼名称: " + word1.header);
        assertTrue(word1.allText().contains(building), "正文标题也应含公寓楼名称");
        assertTrue(word1.allText().contains("APT8-HT-0001"), "明细表应循环出租约行（合同号1）");
        assertTrue(word1.allText().contains("APT8-HT-0002"), "明细表应循环出租约行（合同号2）");
        assertTrue(word1.allText().contains("3000.00") && word1.allText().contains("3200.00"),
                "明细应含各月租金额");
        assertTrue(word1.allText().contains("6200.00"), "合计行应自动汇总为 6200.00");
        assertTrue(word1.allText().contains("本期签约共 2 条"), "合计行应显示明细条数");
        // 数据行恰好两条（不含表头/合计），且房号随行内字段一起渲染
        List<List<String>> dataRows1 = word1.dataRowsContaining("APT8-HT-0001");
        List<List<String>> dataRows2 = word1.dataRowsContaining("APT8-HT-0002");
        assertEquals(1, dataRows1.size());
        assertEquals(1, dataRows2.size());
        assertTrue(dataRows1.get(0).contains("101"), "合同1所在行应含房号101: " + dataRows1.get(0));
        assertTrue(dataRows2.get(0).contains("102"), "合同2所在行应含房号102: " + dataRows2.get(0));

        // 第二版：版本号 +1；两份历史文件都在磁盘且字节不同（时间戳/版本不同），不被覆盖
        Path file1 = Paths.get(versionMapper.selectById(v1).getFilePath());
        assertTrue(Files.isRegularFile(file1), "v1 文件应已落归档目录");
        long v2 = generateAndExpectVersion(demoToken, building, "示例小区", 2, 2, 6200.00);
        Path file2 = Paths.get(versionMapper.selectById(v2).getFilePath());
        assertTrue(Files.isRegularFile(file2), "v2 文件应已落归档目录");
        assertFalse(file1.equals(file2), "两版文件路径应不同");
        assertTrue(Files.isRegularFile(file1), "生成 v2 后 v1 历史报告必须仍在（不被覆盖）");
        byte[] docx1After = Files.readAllBytes(file1);
        assertEquals(java.util.Arrays.equals(docx1, docx1After), true, "v1 历史文件内容不得被改写");

        // 版本列表：demo01 能看到这栋楼两版
        MvcResult list = mockMvc.perform(get("/api/apartment/sign-reports").header("X-Auth-Token", demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rentTotal").value(6200.0))
                .andExpect(jsonPath("$.data[1].rentTotal").value(6200.0))
                .andReturn();
        JsonNode arr = readNode(list).path("data");
        java.util.List<Integer> versions = new ArrayList<>();
        arr.forEach(n -> versions.add(n.path("versionNo").asInt()));
        assertTrue(versions.contains(1) && versions.contains(2), "列表应含两版: " + versions);
    }

    @Test
    void emptyDetails_totalRowShowsZeroAndNoError() throws Exception {
        String building = "APT8空楼";
        registerLedger(supervisorToken, "APT8-P-EMPTY", "示例小区", building, "201");
        // 该楼无任何租约：空明细不报错，合计 0
        long id = generateAndExpectVersion(demoToken, building, "示例小区", 1, 0, 0.0);
        WordText word = extractWord(download(demoToken, id));
        assertTrue(word.allText().contains("0.00"), "空明细合计行应显示 0.00: " + word.allText());
        assertTrue(word.allText().contains("本期签约共 0 条"));
        assertFalse(word.allText().contains("APT8-HT"), "不应出现任何租约明细行");
        assertTrue(word.header.contains(building));
    }

    @Test
    void dataScope_crossCommunityForbiddenAndHiddenFromList() throws Exception {
        // 主管在别的小区登记并由管家建档，再由主管（ALL）出一份报告
        registerLedger(supervisorToken, "APT8-P-OTHER", "别的小区", "APT8跨区楼", "301");
        createLease(stewardToken, "APT8-HT-OTHER", "APT8-P-OTHER", "2026-03-01", "2027-02-28", 5000);
        long otherId = generateAndExpectVersion(supervisorToken, "APT8跨区楼", "别的小区", 1, 1, 5000.0);

        // demo01（SCOPE=示例小区）对跨片区楼栋生成：403
        mockMvc.perform(post("/api/apartment/sign-reports/generate")
                        .header("X-Auth-Token", demoToken).contentType("application/json")
                        .content(generateJson("APT8跨区楼", "别的小区")))
                .andExpect(status().isForbidden());
        // 不带小区也应被台账归集后拦下
        mockMvc.perform(post("/api/apartment/sign-reports/generate")
                        .header("X-Auth-Token", demoToken).contentType("application/json")
                        .content(generateJson("APT8跨区楼", null)))
                .andExpect(status().isForbidden());

        // 列表不见跨片区报告；直接详情/下载也 403
        MvcResult list = mockMvc.perform(get("/api/apartment/sign-reports").header("X-Auth-Token", demoToken))
                .andExpect(status().isOk()).andReturn();
        assertFalse(readNode(list).path("data").toString().contains("APT8跨区楼"), "SCOPE 列表不应含跨片区报告");
        mockMvc.perform(get("/api/apartment/sign-reports/" + otherId).header("X-Auth-Token", demoToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/apartment/sign-reports/" + otherId + "/download")
                        .header("X-Auth-Token", demoToken))
                .andExpect(status().isForbidden());

        // 主管（ALL）详情与下载正常
        mockMvc.perform(get("/api/apartment/sign-reports/" + otherId).header("X-Auth-Token", supervisorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.buildingName").value("APT8跨区楼"));
    }

    // ---------------- helpers ----------------

    private long generateAndExpectVersion(String token, String building, String community,
                                          int expectVersion, int expectLeases, double expectTotal)
            throws Exception {
        MvcResult r = mockMvc.perform(post("/api/apartment/sign-reports/generate")
                        .header("X-Auth-Token", token).contentType("application/json")
                        .content(generateJson(building, community)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.versionNo").value(expectVersion))
                .andExpect(jsonPath("$.data.leaseCount").value(expectLeases))
                .andReturn();
        JsonNode data = readNode(r).path("data");
        assertEquals(expectTotal, data.path("rentTotal").asDouble(), 0.001, "租金合计不匹配: " + data);
        return data.path("id").asLong();
    }

    private byte[] download(String token, long id) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/apartment/sign-reports/" + id + "/download")
                        .header("X-Auth-Token", token))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsByteArray();
    }

    private String generateJson(String building, String community) {
        String communityPart = community == null ? "" : ",\"community\":\"" + community + "\"";
        return "{\"buildingName\":\"" + building + "\"" + communityPart
                + ",\"requestNo\":\"REQ-RPT-" + System.nanoTime() + "\",\"operator\":\"ignored\"}";
    }

    private void registerLedger(String token, String code, String community, String building, String room)
            throws Exception {
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", token).contentType("application/json")
                        .content("{\"propertyCode\":\"" + code + "\",\"community\":\"" + community
                                + "\",\"building\":\"" + building + "\",\"roomNo\":\"" + room
                                + "\",\"area\":50.5,\"requestNo\":\"REQ-" + code + "\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    private void createLease(String token, String contractNo, String propertyCode,
                             String start, String end, int rent) throws Exception {
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", token).contentType("application/json")
                        .content("{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"" + propertyCode
                                + "\",\"tenantName\":\"李四\",\"tenantPhone\":\"13900000000\","
                                + "\"tenantIdCard\":\"110101199002021234\",\"leaseStart\":\"" + start
                                + "\",\"leaseEnd\":\"" + end + "\",\"monthlyRent\":" + rent
                                + ",\"deposit\":1000,\"requestNo\":\"REQ-" + contractNo
                                + "\",\"operator\":\"ignored\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return readNode(result).path("data").path("token").asText();
    }

    private JsonNode readNode(MvcResult result) throws Exception {
        // application/json 未显式带 charset，getContentAsString() 会按 ISO-8859-1 把中文读成乱码；
        // 统一按 UTF-8 读字节（响应体本就是 UTF-8）。
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> toStringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** 提取 docx 的页眉文本与各表格行文本，用于断言渲染结果。 */
    private static WordText extractWord(byte[] docx) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder header = new StringBuilder();
            for (XWPFHeader h : doc.getHeaderList()) {
                header.append(h.getText());
            }
            List<List<String>> tables = new ArrayList<>();
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow tr : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell tc : tr.getTableCells()) {
                        cells.add(tc.getText());
                    }
                    tables.add(cells);
                }
            }
            StringBuilder body = new StringBuilder();
            doc.getParagraphs().forEach(p -> body.append(p.getText()));
            return new WordText(header.toString(), body.toString(), tables);
        }
    }

    private static class WordText {
        final String header;
        final String body;
        final List<List<String>> tables;

        WordText(String header, String body, List<List<String>> tables) {
            this.header = header;
            this.body = body;
            this.tables = tables;
        }

        String allText() {
            StringBuilder sb = new StringBuilder(header).append(body);
            for (List<String> row : tables) {
                for (String c : row) {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /** 所有表格中含指定文本的数据行（单元格列表）。 */
        List<List<String>> dataRowsContaining(String text) {
            List<List<String>> hit = new ArrayList<>();
            for (List<String> row : tables) {
                for (String c : row) {
                    if (c != null && c.contains(text)) {
                        hit.add(row);
                        break;
                    }
                }
            }
            return hit;
        }
    }
}
