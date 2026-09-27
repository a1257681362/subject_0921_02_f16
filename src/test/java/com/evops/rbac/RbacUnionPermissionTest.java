package com.evops.rbac;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.rbac.entity.DataScopeAudit;
import com.evops.rbac.mapper.DataScopeAuditMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-3 多角色权限并集端到端：
 * 1. 菜单/按钮并集：管家∩主管叠加取并集；
 * 2. 数据范围按最宽角色放行（SELF/SCOPE/ALL）并逐请求落审计；
 * 3. 角色停用即时收权（令牌不变、下次请求即缩权），恢复后复权；
 * 4. 账号停用令牌即时失效，重新启用需重新登录；
 * 5. 未登录 401、已登录权限不足 403。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_rbac_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RbacUnionPermissionTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataScopeAuditMapper auditMapper;

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

    private String ledgerJson(String code, String community) {
        return "{\"propertyCode\":\"" + code + "\",\"community\":\"" + community + "\","
                + "\"building\":\"1栋\",\"roomNo\":\"101\",\"area\":45.5,"
                + "\"requestNo\":\"REQ-L-" + code + "\",\"operator\":\"ignored-by-auth\"}";
    }

    private String leaseJson(String contractNo, String propertyCode) {
        return "{\"contractNo\":\"" + contractNo + "\",\"propertyCode\":\"" + propertyCode + "\","
                + "\"tenantName\":\"张三\",\"tenantPhone\":\"13800000000\","
                + "\"leaseStart\":\"2026-01-01\",\"leaseEnd\":\"2026-12-31\","
                + "\"monthlyRent\":3000,\"deposit\":1000,"
                + "\"requestNo\":\"REQ-" + contractNo + "\",\"operator\":\"ignored-by-auth\"}";
    }

    @Test
    @Order(1)
    void login_unionMenusAndButtons_andFunctionPermission() throws Exception {
        // 错口令 / 无令牌
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"steward01\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/apartment/leases"))
                .andExpect(status().isUnauthorized());

        String steward = login("steward01", "steward123");
        String supervisor = login("supervisor01", "supervisor123");
        String combo = login("combo01", "combo123");

        // 单管家：apt-7 再增额度菜单 + 申请/确认/取消/查看 4 按钮（不含配额调整），
        // apt-10 再增房源归属树菜单 + 维护/查询 2 按钮 → 9 菜单 20 按钮，范围 SELF
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", steward))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("steward"))
                .andExpect(jsonPath("$.data.menus", hasSize(9)))
                .andExpect(jsonPath("$.data.buttons", hasSize(20)))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:quota:apply")))
                .andExpect(jsonPath("$.data.buttons",
                        not(hasItem("apartment:quota:adjust"))))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:property-tree:manage")))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:property-tree:view")))
                .andExpect(jsonPath("$.data.dataScope.level").value("SELF"))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:lease:create")))
                .andExpect(jsonPath("$.data.menus",
                        hasItem("apartment:menu:ops-log")))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:opslog:view")))
                .andExpect(jsonPath("$.data.buttons",
                        not(hasItem("apartment:property:register"))));

        // 单主管：apt-7 增额度菜单 + 5 按钮（含配额调整），apt-8 增签约报告菜单 + 生成/查看 2 按钮，
        // apt-10 增房源归属树菜单 + 维护/查询 2 按钮，仍不给运维日志入口 → 9 菜单 25 按钮，范围 ALL
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", supervisor))
                .andExpect(jsonPath("$.data.roles[0]").value("supervisor"))
                .andExpect(jsonPath("$.data.menus", hasSize(9)))
                .andExpect(jsonPath("$.data.buttons", hasSize(25)))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:quota:adjust")))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:sign-report:generate")))
                .andExpect(jsonPath("$.data.menus",
                        hasItem("apartment:menu:sign-report")))
                .andExpect(jsonPath("$.data.menus",
                        not(hasItem("apartment:menu:ops-log"))))
                .andExpect(jsonPath("$.data.buttons",
                        not(hasItem("apartment:opslog:view"))))
                .andExpect(jsonPath("$.data.dataScope.level").value("ALL"));

        // 管家+主管叠加：角色两个；菜单取并集（主管 apt-8 报告菜单 + 管家运维日志菜单 +
        // 双方共有 apt-10 归属树菜单），按钮并集（主管 25 + 管家运维日志 1 = 26），范围 ALL
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", combo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", hasSize(2)))
                .andExpect(jsonPath("$.data.menus", hasSize(10)))
                .andExpect(jsonPath("$.data.buttons", hasSize(26)))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:opslog:view")))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:property:register")))
                .andExpect(jsonPath("$.data.buttons",
                        hasItem("apartment:lease:export")))
                .andExpect(jsonPath("$.data.dataScope.level").value("ALL"))
                .andExpect(jsonPath("$.data.dataScope.grantedBy[0]").value("supervisor"));

        // 功能权限：管家无台账登记 → 403；并集后的 combo 有 → 200（同时造示例小区台账）
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(ledgerJson("PROP-A-101", "示例小区")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", combo)
                        .contentType("application/json")
                        .content(ledgerJson("PROP-A-101", "示例小区")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operator").value("combo01"));
    }

    @Test
    @Order(2)
    void dataScope_unionWidestFiltersAndAudits() throws Exception {
        String admin = login("admin", "admin123");
        String steward = login("steward01", "steward123");
        String area = login("area01", "area123");
        String combo = login("combo01", "combo123");

        // 另一小区台账（combo=ALL 可登记）
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", combo)
                        .contentType("application/json")
                        .content(ledgerJson("PROP-B-202", "其他小区")))
                .andExpect(status().isOk());

        // 租约：管家本人 1 条（示例小区），管理员在两个小区各 1 条
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(leaseJson("HT-RBAC-SELF", "PROP-A-101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operator").value("steward01"));
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content(leaseJson("HT-RBAC-ADMIN-A", "PROP-A-101")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content(leaseJson("HT-RBAC-ADMIN-B", "PROP-B-202")))
                .andExpect(status().isOk());

        // SELF：管家只见本人经办的 1 条
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", steward))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].contractNo").value("HT-RBAC-SELF"));

        // SCOPE：片区管家只见示例小区 2 条，见不到其他小区
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", area))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        // 越片区建档：403
        mockMvc.perform(post("/api/apartment/leases")
                        .header("X-Auth-Token", area)
                        .contentType("application/json")
                        .content(leaseJson("HT-RBAC-AREA-B", "PROP-B-202")))
                .andExpect(status().isForbidden());
        // 导出口径同检索：含示例小区、不含其他小区
        String csv = mockMvc.perform(get("/api/apartment/leases/export").header("X-Auth-Token", area))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("HT-RBAC-ADMIN-A"));
        org.junit.jupiter.api.Assertions.assertFalse(csv.contains("HT-RBAC-ADMIN-B"));

        // ALL：并集后的 combo 见全部 3 条
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", combo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)));

        // SELF 写操作越权：管家不能退管理员经办的租约（先于业务校验被挡）
        mockMvc.perform(post("/api/apartment/leases/checkout")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"contractNo\":\"HT-RBAC-ADMIN-A\",\"checkoutDate\":\"2026-09-22\","
                                + "\"inspectionResult\":\"PASS\",\"requestNo\":\"REQ-CK-X\","
                                + "\"operator\":\"ignored\"}"))
                .andExpect(status().isForbidden());
        // 本人经办：可退
        mockMvc.perform(post("/api/apartment/leases/checkout")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content("{\"contractNo\":\"HT-RBAC-SELF\",\"checkoutDate\":\"2026-09-22\","
                                + "\"inspectionResult\":\"PASS\",\"requestNo\":\"REQ-CK-SELF\","
                                + "\"operator\":\"ignored\"}"))
                .andExpect(status().isOk());

        // 放行审计：逐请求可查，最宽范围与来源角色均留痕
        assertLatestAudit("steward01", "apartment:lease:view", "SELF", null, null, 1);
        assertLatestAudit("area01", "apartment:lease:view", "SCOPE", "area_steward", "示例小区", 2);
        assertLatestAudit("combo01", "apartment:lease:view", "ALL", "supervisor", null, 3);
    }

    @Test
    @Order(3)
    void roleDisable_takesEffectOnNextRequest_andRestores() throws Exception {
        String admin = login("admin", "admin123");
        String combo = login("combo01", "combo123");

        // 停用主管角色：combo 的令牌不变，下一次请求并集立即缩为管家
        mockMvc.perform(post("/api/rbac/roles/supervisor/status")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", combo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", hasSize(1)))
                .andExpect(jsonPath("$.data.roles[0]").value("steward"))
                .andExpect(jsonPath("$.data.buttons", hasSize(20)))
                .andExpect(jsonPath("$.data.dataScope.level").value("SELF"));
        // 主管角色的按钮即时收回
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", combo)
                        .contentType("application/json")
                        .content(ledgerJson("PROP-A-102", "示例小区")))
                .andExpect(status().isForbidden());

        // 恢复主管角色：同一令牌下次请求即复权（并集与 ALL 范围回来）
        mockMvc.perform(post("/api/rbac/roles/supervisor/status")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"active\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", combo))
                .andExpect(jsonPath("$.data.roles", hasSize(2)))
                .andExpect(jsonPath("$.data.buttons", hasSize(26)))
                .andExpect(jsonPath("$.data.dataScope.level").value("ALL"));
    }

    @Test
    @Order(4)
    void accountDisable_revokesTokenImmediately_reenableRequiresRelogin() throws Exception {
        String admin = login("admin", "admin123");
        String steward = login("steward01", "steward123");

        // 停用前令牌可用
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", steward))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/rbac/users/steward01/status")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        // 当次请求即收权：旧令牌 401，重新登录也被拒
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", steward))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"steward01\",\"password\":\"steward123\"}"))
                .andExpect(status().isUnauthorized());

        // 重新启用：旧令牌在停用窗口已吊销，必须重新登录
        mockMvc.perform(post("/api/rbac/users/steward01/status")
                        .header("X-Auth-Token", admin)
                        .contentType("application/json")
                        .content("{\"active\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", steward))
                .andExpect(status().isUnauthorized());
        String reLogin = login("steward01", "steward123");
        mockMvc.perform(get("/api/apartment/leases").header("X-Auth-Token", reLogin))
                .andExpect(status().isOk());
    }

    private void assertLatestAudit(String username, String resource, String scope,
                                   String grantedBy, String scopeValue, int count) {
        List<DataScopeAudit> rows = auditMapper.selectList(new QueryWrapper<DataScopeAudit>()
                .eq("username", username)
                .eq("resource", resource)
                .orderByDesc("id")
                .last("LIMIT 1"));
        assertEquals(1, rows.size(), "应存在放行审计: " + username + "/" + resource);
        DataScopeAudit audit = rows.get(0);
        assertEquals(scope, audit.getWidestScope());
        assertEquals(grantedBy, audit.getGrantedByRole());
        assertEquals(scopeValue, audit.getScopeValue());
        assertEquals(count, audit.getResultCount());
        assertNotNull(audit.getRequestTime());
    }
}
