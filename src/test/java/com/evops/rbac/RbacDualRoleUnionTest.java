package com.evops.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * apt-13 双角色并集回归守卫（工单：兼主管+管家账号菜单变少、疑按交集计算——核实不成立，用例固化口径）。
 *
 * 口径（apt-3 起确立，本类直接以「交集反例」做断言，任何人把并集改成交集都会立刻红）：
 * 1. 菜单/按钮 = 全部启用角色的并集：双角色账号必须同时看到两个角色的**独占**菜单
 *    （管家独占 apartment:menu:ops-log、主管独占 apartment:menu:sign-report），
 *    且菜单数严格多于任一单角色——若退化为交集，双角色只剩 8 个共有菜单，本类即失败；
 * 2. 功能权限同口径：双角色可直达两个角色各自的专属接口；
 * 3. 默认拒绝：匿名/错口令 401，单角色越权 403，双角色对双方都未授的权限点
 *    （如 rbac:admin:manage、apartment:sensitive:manage）仍然 403/不出现在并集。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_rbac_dual_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RbacDualRoleUnionTest {
    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void dualRole_menusAndButtonsAreUnion_notIntersection() throws Exception {
        String steward = login("steward01", "steward123");
        String supervisor = login("supervisor01", "supervisor123");
        String combo = login("combo01", "combo123");

        // 单管家 9 菜单：独占运维日志菜单，无签约报告菜单
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", steward))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", hasSize(1)))
                .andExpect(jsonPath("$.data.menus", hasSize(9)))
                .andExpect(jsonPath("$.data.menus", hasItem("apartment:menu:ops-log")))
                .andExpect(jsonPath("$.data.menus", not(hasItem("apartment:menu:sign-report"))))
                .andExpect(jsonPath("$.data.buttons", hasSize(20)));

        // 单主管 9 菜单：独占签约报告菜单，无运维日志菜单
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", hasSize(1)))
                .andExpect(jsonPath("$.data.menus", hasSize(9)))
                .andExpect(jsonPath("$.data.menus", hasItem("apartment:menu:sign-report")))
                .andExpect(jsonPath("$.data.menus", not(hasItem("apartment:menu:ops-log"))))
                .andExpect(jsonPath("$.data.buttons", hasSize(25)));

        // 双角色 = 并集 10 菜单：两个角色的独占菜单必须同时在列（交集只剩 8 个共有菜单）
        mockMvc.perform(get("/api/auth/me").header("X-Auth-Token", combo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", hasSize(2)))
                .andExpect(jsonPath("$.data.menus", hasSize(10)))
                .andExpect(jsonPath("$.data.menus", hasItem("apartment:menu:ops-log")))
                .andExpect(jsonPath("$.data.menus", hasItem("apartment:menu:sign-report")))
                .andExpect(jsonPath("$.data.buttons", hasSize(26)))
                // 双方独占按钮都并入：管家 opslog:view；主管 register / quota:adjust / sign-report:generate
                .andExpect(jsonPath("$.data.buttons", hasItem("apartment:opslog:view")))
                .andExpect(jsonPath("$.data.buttons", hasItem("apartment:property:register")))
                .andExpect(jsonPath("$.data.buttons", hasItem("apartment:quota:adjust")))
                .andExpect(jsonPath("$.data.buttons", hasItem("apartment:sign-report:generate")))
                // 默认拒绝：两个角色都没授的点不进并集（敏感词库管理仅管理员）
                .andExpect(jsonPath("$.data.buttons", not(hasItem("apartment:sensitive:manage"))))
                .andExpect(jsonPath("$.data.buttons", not(hasItem("rbac:admin:manage"))))
                // 数据范围并集取最宽：主管 ALL 放行
                .andExpect(jsonPath("$.data.dataScope.level").value("ALL"));
    }

    @Test
    void dualRole_functionPermissionFollowsUnion_defaultDenyElsewhere() throws Exception {
        // 默认拒绝：匿名 401、错口令 401
        mockMvc.perform(get("/api/apartment/leases"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"combo01\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        String steward = login("steward01", "steward123");
        String supervisor = login("supervisor01", "supervisor123");
        String combo = login("combo01", "combo123");

        String ledgerJson = "{\"propertyCode\":\"DUAL-A-101\",\"community\":\"示例小区\","
                + "\"building\":\"1栋\",\"roomNo\":\"101\",\"area\":45.5,"
                + "\"requestNo\":\"REQ-DUAL-001\",\"operator\":\"ignored-by-auth\"}";

        // 单角色越权 403（默认拒绝）：管家不能登记台账，主管不能查运维日志
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", steward)
                        .contentType("application/json")
                        .content(ledgerJson))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/apartment/ops-log/entries?traceId=dualtest01")
                        .header("X-Auth-Token", supervisor))
                .andExpect(status().isForbidden());

        // 双角色并集：两个角色的专属接口都可达
        mockMvc.perform(post("/api/apartment/property/entries")
                        .header("X-Auth-Token", combo)
                        .contentType("application/json")
                        .content(ledgerJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operator").value("combo01"));
        mockMvc.perform(get("/api/apartment/ops-log/entries?traceId=dualtest01")
                        .header("X-Auth-Token", combo))
                .andExpect(status().isOk());

        // 双角色对双方都未授的权限点仍默认拒绝：账号角色启停管理（仅管理员）
        mockMvc.perform(post("/api/rbac/users/steward01/status")
                        .header("X-Auth-Token", combo)
                        .contentType("application/json")
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
    }
}
