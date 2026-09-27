package com.evops.apartment.tree;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.opslog.OpsLogEntry;
import com.evops.common.opslog.OpsLogEntryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
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
 * apt-10 房源归属树闭包表端到端（SpringBootTest + MockMvc，H2 内存库）：
 * 1. 祖先链“根 → 自己”顺序正确（含 distance 递减）；
 * 2. 子树查询走闭包表，取全且只取该子树；
 * 3. 同级排序走 sort_order，排序接口即时生效；
 * 4. 拖拽换父子孙自动跟随（闭包边与整子树小区归属一起迁），也支持挪为根；
 * 5. 环状引用（挂自身/挂子孙）直接拒绝且结构不变；
 * 6. 维护日志带合同号，运维日志可按合同号查到（验收必查）；
 * 7. 401/403、SCOPE 跨片区 403、demo01 最小授权、schema 重跑幂等。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evops_apt10_test;DB_CLOSE_DELAY=-1",
        "evops.ops-log.flush-interval-ms=100"
})
@AutoConfigureMockMvc
class PropertyClosureTreeFlowTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OpsLogEntryMapper opsLogMapper;
    @Autowired private DataSource dataSource;

    private String stewardToken;
    private String supervisorToken;
    private String areaToken;
    private String demoToken;

    @BeforeEach
    void setUp() throws Exception {
        stewardToken = login("steward01", "steward123");
        supervisorToken = login("supervisor01", "supervisor123");
        areaToken = login("area01", "area123");
        demoToken = login("demo01", "demo123");
    }

    @Test
    void ancestorChain_returnsRootToSelfInCorrectOrder() throws Exception {
        // 示例小区 / 2栋 / 3层 / 301
        long community = createNode(stewardToken, node("T1-C", "示例小区", "COMMUNITY", "示例小区", null, 0, null));
        long building = createNode(stewardToken, node("T1-B", "2栋", "BUILDING", null, community, 0, null));
        long floor = createNode(stewardToken, node("T1-F", "3层", "FLOOR", null, building, 0, null));
        long room = createNode(stewardToken, node("T1-R", "301", "ROOM", null, floor, 0, null));

        MvcResult result = mockMvc.perform(get("/api/apartment/property-tree/nodes/" + room + "/ancestors")
                        .header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode chain = readData(result);
        assertEquals(4, chain.size(), "祖先链含自身共 4 级");
        assertEquals(community, chain.get(0).get("id").asLong(), "第一级是根（小区）");
        assertEquals(building, chain.get(1).get("id").asLong());
        assertEquals(floor, chain.get(2).get("id").asLong());
        assertEquals(room, chain.get(3).get("id").asLong(), "最后一级是自己");
        // distance 从最远（3）递减到自身（0），顺序由闭包表 ORDER BY distance DESC 保证
        assertEquals(3, chain.get(0).get("distance").asInt());
        assertEquals(2, chain.get(1).get("distance").asInt());
        assertEquals(1, chain.get(2).get("distance").asInt());
        assertEquals(0, chain.get(3).get("distance").asInt());
    }

    @Test
    void subtree_returnsWholeSubtreeAndOnlyIt() throws Exception {
        long root = createNode(stewardToken, node("T2-R", "根", "COMMUNITY", "示例小区", null, 0, null));
        long a = createNode(stewardToken, node("T2-A", "甲栋", "BUILDING", null, root, 10, null));
        long b = createNode(stewardToken, node("T2-B", "乙栋", "BUILDING", null, root, 20, null));
        long a1 = createNode(stewardToken, node("T2-A1", "甲栋101", "ROOM", null, a, 0, null));

        // 全树：根 + 甲栋 + 乙栋 + 甲栋101，且按层、同级 sort_order 排
        List<Long> whole = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + root + "/subtree")
                .header("X-Auth-Token", stewardToken)).andExpect(status().isOk()).andReturn()));
        assertEquals(java.util.Arrays.asList(root, a, b, a1), whole);

        // 甲栋子树只含甲栋及其房间，不含根/乙栋
        List<Long> subA = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + a + "/subtree")
                .header("X-Auth-Token", stewardToken)).andExpect(status().isOk()).andReturn()));
        assertEquals(java.util.Arrays.asList(a, a1), subA);
        assertFalse(subA.contains(root));
        assertFalse(subA.contains(b));
    }

    @Test
    void siblings_orderedBySortOrder_andSortApiReorders() throws Exception {
        long root = createNode(stewardToken, node("T3-R", "根", "COMMUNITY", "示例小区", null, 0, null));
        long c30 = createNode(stewardToken, node("T3-30", "30号", "ROOM", null, root, 30, null));
        long c10 = createNode(stewardToken, node("T3-10", "10号", "ROOM", null, root, 10, null));
        long c20 = createNode(stewardToken, node("T3-20", "20号", "ROOM", null, root, 20, null));

        List<Long> ordered = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes")
                .header("X-Auth-Token", stewardToken).param("parentId", String.valueOf(root)))
                .andExpect(status().isOk()).andReturn()));
        assertEquals(java.util.Arrays.asList(c10, c20, c30), ordered, "同级按 sort_order 升序");

        // 把 30 号挪到最前：只改排序，不动归属
        mockMvc.perform(post("/api/apartment/property-tree/nodes/sort")
                        .header("X-Auth-Token", stewardToken)
                        .contentType("application/json")
                        .content(sortJson(c30, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortOrder").value(5));
        List<Long> reordered = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes")
                .header("X-Auth-Token", stewardToken).param("parentId", String.valueOf(root)))
                .andExpect(status().isOk()).andReturn()));
        assertEquals(java.util.Arrays.asList(c30, c10, c20), reordered);
    }

    @Test
    void moveNode_descendantsFollow_closureAndCommunityMigrate() throws Exception {
        // 主管（ALL）跨两个小区建两棵树
        long oldRoot = createNode(supervisorToken, node("T4-OLD", "旧小区", "COMMUNITY", "旧小区", null, 0, null));
        long building = createNode(supervisorToken, node("T4-B", "1栋", "BUILDING", null, oldRoot, 0, null));
        long floor = createNode(supervisorToken, node("T4-F", "2层", "FLOOR", null, building, 0, null));
        long room = createNode(supervisorToken, node("T4-R", "201", "ROOM", null, floor, 0, null));
        long newRoot = createNode(supervisorToken, node("T4-NEW", "新小区", "COMMUNITY", "其他小区", null, 0, null));

        // 把 1栋 整体拖到新小区根下
        mockMvc.perform(post("/api/apartment/property-tree/nodes/move")
                        .header("X-Auth-Token", supervisorToken)
                        .contentType("application/json")
                        .content(moveJson(building, newRoot, 0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentId").value(newRoot))
                .andExpect(jsonPath("$.data.community").value("其他小区"));

        // 子孙自动跟随：2层/201 的祖先链顶部已是新小区，且不再含旧小区
        JsonNode floorChain = readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + floor + "/ancestors")
                .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn());
        List<Long> floorAnc = ids(floorChain);
        assertEquals(newRoot, floorAnc.get(0), "2层的根祖先变为新小区");
        assertEquals(building, floorAnc.get(floorAnc.size() - 2));
        assertEquals(floor, floorAnc.get(floorAnc.size() - 1));
        assertFalse(floorAnc.contains(oldRoot), "旧小区已不在 2层 祖先链中");

        JsonNode roomChain = readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + room + "/ancestors")
                .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn());
        List<Long> roomAnc = ids(roomChain);
        assertEquals(java.util.Arrays.asList(newRoot, building, floor, room), roomAnc);

        // 子树归属：新根子树含整体迁入的 1栋；旧根子树不再含它们
        List<Long> newSub = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + newRoot + "/subtree")
                .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn()));
        assertTrue(newSub.contains(building) && newSub.contains(floor) && newSub.contains(room));
        List<Long> oldSub = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + oldRoot + "/subtree")
                .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn()));
        assertEquals(java.util.Collections.singletonList(oldRoot), oldSub, "旧根下只剩自己");

        // 整子树小区冗余列跟随更新（SCOPE 过滤口径随新小区走）
        JsonNode newSubNode = readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + newRoot + "/subtree")
                .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn());
        for (JsonNode n : newSubNode) {
            assertEquals("其他小区", n.get("community").asText());
        }
    }

    @Test
    void moveNode_toRoot_detachesWholeSubtree() throws Exception {
        long root = createNode(supervisorToken, node("T5-R", "小区", "COMMUNITY", "旧小区", null, 0, null));
        long building = createNode(supervisorToken, node("T5-B", "1栋", "BUILDING", null, root, 0, null));
        long room = createNode(supervisorToken, node("T5-RM", "101", "ROOM", null, building, 0, null));

        mockMvc.perform(post("/api/apartment/property-tree/nodes/move")
                        .header("X-Auth-Token", supervisorToken)
                        .contentType("application/json")
                        .content(moveJson(building, null, 0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 1栋成为根：101 的祖先链为 1栋 → 101；根列表含 1栋
        List<Long> chain = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + room + "/ancestors")
                .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn()));
        assertEquals(java.util.Arrays.asList(building, room), chain);
        List<Long> roots = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes")
                .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn()));
        assertTrue(roots.contains(building));
    }

    @Test
    void cycle_moveUnderSelfOrDescendant_rejected_andStructureUnchanged() throws Exception {
        long root = createNode(supervisorToken, node("T6-R", "小区", "COMMUNITY", "示例小区", null, 0, null));
        long building = createNode(supervisorToken, node("T6-B", "1栋", "BUILDING", null, root, 0, null));
        long floor = createNode(supervisorToken, node("T6-F", "2层", "FLOOR", null, building, 0, null));

        // 挂到自己子孙（2层）下：拒绝
        mockMvc.perform(post("/api/apartment/property-tree/nodes/move")
                        .header("X-Auth-Token", supervisorToken)
                        .contentType("application/json")
                        .content(moveJson(building, floor, 0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("环状引用")));

        // 挂到自身下：拒绝
        mockMvc.perform(post("/api/apartment/property-tree/nodes/move")
                        .header("X-Auth-Token", supervisorToken)
                        .contentType("application/json")
                        .content(moveJson(building, building, 0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("环状引用")));

        // 结构不变：1栋祖先链仍是 小区 → 1栋；2层仍是 小区 → 1栋 → 2层
        assertEquals(java.util.Arrays.asList(root, building),
                ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + building + "/ancestors")
                        .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn())));
        assertEquals(java.util.Arrays.asList(root, building, floor),
                ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes/" + floor + "/ancestors")
                        .header("X-Auth-Token", supervisorToken)).andExpect(status().isOk()).andReturn())));
    }

    @Test
    void maintenanceLogs_queryableByContractNo_acceptanceMustCheck() throws Exception {
        String contractNo = "HT-TREE-LOG-0001";
        long root = createNode(stewardToken, node("T7-R", "合同小区", "COMMUNITY", "示例小区", null, 0, contractNo));
        long building = createNode(stewardToken, node("T7-B", "1栋", "BUILDING", null, root, 0, contractNo));
        // 换父动作也带同一合同号（先建个新父）
        long sibling = createNode(stewardToken, node("T7-B2", "2栋", "BUILDING", null, root, 0, contractNo));
        mockMvc.perform(post("/api/apartment/property-tree/nodes/move")
                        .header("X-Auth-Token", stewardToken)
                        .contentType("application/json")
                        .content(moveJson(building, sibling, 0, contractNo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        // 验收必查：运维日志按合同号能拉出本合同的新增/换父日志
        awaitOpsLog("房源归属树新增节点", contractNo, 5000);
        awaitOpsLog("房源归属树换父成功", contractNo, 5000);

        MvcResult result = mockMvc.perform(get("/api/apartment/ops-log/entries")
                        .header("X-Auth-Token", stewardToken)
                        .param("contractNo", contractNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode data = readNode(result);
        assertTrue(data.get("count").asInt() >= 2, "按合同号至少查到新增与换父两类日志");
        boolean sawCreate = false;
        boolean sawMove = false;
        for (JsonNode row : data.get("rows")) {
            assertEquals(contractNo, row.get("contractNo").asText());
            assertEquals("steward01", row.get("username").asText());
            String msg = row.get("message").asText();
            if (msg.contains("房源归属树新增节点")) {
                sawCreate = true;
            }
            if (msg.contains("房源归属树换父成功")) {
                sawMove = true;
            }
        }
        assertTrue(sawCreate, "应能按合同号查到新增节点日志");
        assertTrue(sawMove, "应能按合同号查到换父成功日志");
    }

    @Test
    void auth_401_403_andScopeCrossCommunity_andDemoLeastPrivilege() throws Exception {
        // 未登录 401
        mockMvc.perform(get("/api/apartment/property-tree/nodes"))
                .andExpect(status().isUnauthorized());

        // demo01（report_demo 仅 33-35）：无归属树任何权限 → 403
        mockMvc.perform(get("/api/apartment/property-tree/nodes").header("X-Auth-Token", demoToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/apartment/property-tree/nodes")
                        .header("X-Auth-Token", demoToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(node("X", "x", "COMMUNITY", "示例小区", null, 0, null))))
                .andExpect(status().isForbidden());

        // SCOPE 片区管家：授权小区内可建根，跨小区 403
        createNode(areaToken, node("T8-AREA", "片区小区根", "COMMUNITY", "示例小区", null, 0, null));
        mockMvc.perform(post("/api/apartment/property-tree/nodes")
                        .header("X-Auth-Token", areaToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                node("T8-OTHER", "越权小区", "COMMUNITY", "其他小区", null, 0, null))))
                .andExpect(status().isForbidden());

        // 主管 ALL：任意小区可查可建
        long otherRoot = createNode(supervisorToken, node("T8-OTHER-ROOT", "其他小区根", "COMMUNITY", "其他小区", null, 0, null));
        // 片区管家看不到其他小区的根（SCOPE 过滤）
        List<Long> areaRoots = ids(readData(mockMvc.perform(get("/api/apartment/property-tree/nodes")
                .header("X-Auth-Token", areaToken)).andExpect(status().isOk()).andReturn()));
        assertFalse(areaRoots.contains(otherRoot), "SCOPE 不应看到授权外小区节点");
    }

    @Test
    void schemaRerun_idempotent_permissionsStable() throws Exception {
        // 旧文件库每次启动都重跑 schema.sql：再执行一遍，归属树权限仍在、report_demo 仍只有 33-35
        ScriptUtils.executeSqlScript(dataSource.getConnection(), new ClassPathResource("schema.sql"));

        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", stewardToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buttons",
                        org.hamcrest.Matchers.hasItem("apartment:property-tree:view")));
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buttons",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("apartment:property-tree:view"))))
                .andExpect(jsonPath("$.data.buttons",
                        org.hamcrest.Matchers.hasItem("apartment:sign-report:view")));
    }

    // ---------------- helpers ----------------

    private void awaitOpsLog(String messagePart, String contractNo, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Long n = opsLogMapper.selectCount(new QueryWrapper<OpsLogEntry>()
                    .eq("contract_no", contractNo)
                    .like("message", messagePart));
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待运维日志落库超时: " + messagePart + " contractNo=" + contractNo);
    }

    private long createNode(String token, ObjectNode body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/apartment/property-tree/nodes")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data").get("id").asLong();
    }

    private ObjectNode node(String code, String name, String type, String community,
                            Long parentId, Integer sortOrder, String contractNo) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("nodeCode", code);
        n.put("nodeName", name);
        n.put("nodeType", type);
        if (community != null) {
            n.put("community", community);
        }
        if (parentId != null) {
            n.put("parentId", parentId);
        }
        n.put("sortOrder", sortOrder);
        if (contractNo != null) {
            n.put("contractNo", contractNo);
        }
        n.put("requestNo", "REQ-" + code + "-" + sortOrder + "-" + (parentId == null ? "R" : parentId));
        n.put("operator", "ignored");
        return n;
    }

    private String moveJson(long nodeId, Long newParentId, int sortOrder, String contractNo) throws Exception {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("nodeId", nodeId);
        if (newParentId != null) {
            n.put("newParentId", newParentId);
        }
        n.put("sortOrder", sortOrder);
        if (contractNo != null) {
            n.put("contractNo", contractNo);
        }
        n.put("requestNo", "REQ-MOVE-" + nodeId + "-" + (newParentId == null ? "ROOT" : newParentId));
        n.put("operator", "ignored");
        return objectMapper.writeValueAsString(n);
    }

    private String sortJson(long nodeId, int sortOrder) {
        return "{\"nodeId\":" + nodeId + ",\"sortOrder\":" + sortOrder
                + ",\"requestNo\":\"REQ-SORT-" + nodeId + "\",\"operator\":\"ignored\"}";
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return readNode(result);
    }

    private JsonNode readNode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
    }

    private List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : array) {
            ids.add(n.get("id").asLong());
        }
        return ids;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data").get("token").asText();
    }
}
