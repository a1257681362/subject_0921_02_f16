package com.evops.apartment.tree.web;

import com.evops.apartment.tree.dto.NodeCreateRequest;
import com.evops.apartment.tree.dto.NodeMoveRequest;
import com.evops.apartment.tree.dto.NodeSortRequest;
import com.evops.apartment.tree.dto.NodeView;
import com.evops.apartment.tree.entity.PropertyNode;
import com.evops.apartment.tree.service.PropertyTreeService;
import com.evops.common.ApiResponse;
import com.evops.rbac.web.RequirePermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 房源归属树（闭包表）：建节点、拖拽换父、同级排序，子树/祖先链/同级查询。
 * 均需登录；维护走 apartment:property-tree:manage，查询走 apartment:property-tree:view。
 */
@RestController
@RequestMapping("/api/apartment/property-tree")
public class PropertyTreeController {
    private final PropertyTreeService treeService;

    public PropertyTreeController(PropertyTreeService treeService) {
        this.treeService = treeService;
    }

    @PostMapping("/nodes")
    @RequirePermission("apartment:property-tree:manage")
    public ApiResponse<PropertyNode> create(@Validated @RequestBody NodeCreateRequest req) {
        return ApiResponse.ok(treeService.create(req));
    }

    @PostMapping("/nodes/move")
    @RequirePermission("apartment:property-tree:manage")
    public ApiResponse<PropertyNode> move(@Validated @RequestBody NodeMoveRequest req) {
        return ApiResponse.ok(treeService.move(req));
    }

    @PostMapping("/nodes/sort")
    @RequirePermission("apartment:property-tree:manage")
    public ApiResponse<PropertyNode> sort(@Validated @RequestBody NodeSortRequest req) {
        return ApiResponse.ok(treeService.sort(req));
    }

    /** 子树（含根，按层、同级 sort_order 排）。 */
    @GetMapping("/nodes/{rootId}/subtree")
    @RequirePermission("apartment:property-tree:view")
    public ApiResponse<List<NodeView>> subtree(@PathVariable Long rootId) {
        return ApiResponse.ok(treeService.subtree(rootId));
    }

    /** 祖先链（含自身，根 → 自己 顺序）。 */
    @GetMapping("/nodes/{nodeId}/ancestors")
    @RequirePermission("apartment:property-tree:view")
    public ApiResponse<List<NodeView>> ancestors(@PathVariable Long nodeId) {
        return ApiResponse.ok(treeService.ancestors(nodeId));
    }

    /** 同级节点；不传 parentId 返回根列表。 */
    @GetMapping("/nodes")
    @RequirePermission("apartment:property-tree:view")
    public ApiResponse<List<PropertyNode>> children(@RequestParam(required = false) Long parentId) {
        return ApiResponse.ok(treeService.children(parentId));
    }
}
