package com.evops.apartment.tree.service;

import com.evops.apartment.service.Audits;
import com.evops.apartment.tree.dto.NodeCreateRequest;
import com.evops.apartment.tree.dto.NodeMoveRequest;
import com.evops.apartment.tree.dto.NodeSortRequest;
import com.evops.apartment.tree.dto.NodeView;
import com.evops.apartment.tree.entity.PropertyNode;
import com.evops.apartment.tree.mapper.PropertyClosureMapper;
import com.evops.apartment.tree.mapper.PropertyNodeMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.common.trace.TraceContext;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 房源归属树（闭包表模型）。
 *
 * 子树查询、祖先链、同级排序全部走表结构（{@link PropertyClosureMapper}/{@link PropertyNodeMapper}）；
 * 拖拽换父在同一事务内“改直接父 → 删旧路径 → 接新路径 → 整子树小区跟随”，子孙自动跟随；
 * 挂到自身或自己子孙下会成环，换父前先做环检测直接拒绝。
 *
 * 维护动作（建节点/换父/排序）打 INFO 日志；请求带 contractNo 时绑到日志上下文，
 * 管家对账可在运维日志菜单按合同号一次拉出该合同相关的全部归属变更。
 * 数据范围沿用 apt-3：SELF 仅本人经办节点，SCOPE 按节点冗余 community 过滤，ALL 全部。
 */
@Service
public class PropertyTreeService {
    private static final Logger log = LoggerFactory.getLogger(PropertyTreeService.class);

    private final PropertyNodeMapper nodeMapper;
    private final PropertyClosureMapper closureMapper;
    private final ApartmentScopeGuard scopeGuard;

    public PropertyTreeService(PropertyNodeMapper nodeMapper,
                               PropertyClosureMapper closureMapper,
                               ApartmentScopeGuard scopeGuard) {
        this.nodeMapper = nodeMapper;
        this.closureMapper = closureMapper;
        this.scopeGuard = scopeGuard;
    }

    /** 新增节点：parentId 为空建根（须给 community），否则挂父下并继承父的小区。 */
    @Transactional(rollbackFor = Exception.class)
    public PropertyNode create(NodeCreateRequest req) {
        String operator = scopeGuard.resolveOperator(req.getOperator());
        String community;
        if (req.getParentId() == null) {
            community = req.getCommunity();
            if (!StringUtils.hasText(community)) {
                throw new IllegalArgumentException("根节点必须指定所属小区");
            }
        } else {
            PropertyNode parent = requireNode(req.getParentId());
            scopeGuard.requireSelfOperated(parent.getOperator());
            community = parent.getCommunity();
        }
        scopeGuard.requireCommunityInScope(community);

        PropertyNode node = new PropertyNode();
        node.setNodeCode(req.getNodeCode());
        node.setNodeName(req.getNodeName());
        node.setNodeType(req.getNodeType());
        node.setCommunity(community);
        node.setParentId(req.getParentId());
        node.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        Audits.fillForInsert(node, req.getRequestNo(), operator, null);
        nodeMapper.insert(node);
        // 自身行 + 继承父的全部祖先边（distance+1）：新节点祖先链一次闭合
        closureMapper.insertSelfEdge(node.getId());
        if (req.getParentId() != null) {
            closureMapper.insertInheritedEdges(node.getId(), req.getParentId());
        }

        withContract(req.getContractNo(), () -> log.info(
                "房源归属树新增节点 nodeId={} code={} parentId={} community={} requestNo={}",
                node.getId(), node.getNodeCode(), node.getParentId(), community, req.getRequestNo()));
        scopeGuard.audit("apartment:property-tree:manage", req.getRequestNo(),
                "action=CREATE,nodeCode=" + req.getNodeCode() + ",community=" + community, null);
        return node;
    }

    /**
     * 拖拽换父。环状引用（挂自身 / 挂到自己子孙下）直接拒绝；
     * 通过则同事务重建闭包路径，子孙边自动跟随，整子树小区跟随新父。
     */
    @Transactional(rollbackFor = Exception.class)
    public PropertyNode move(NodeMoveRequest req) {
        PropertyNode node = requireNode(req.getNodeId());
        String operator = scopeGuard.resolveOperator(req.getOperator());
        scopeGuard.requireSelfOperated(node.getOperator());

        Long newParentId = req.getNewParentId();
        String newCommunity = node.getCommunity();
        if (newParentId != null) {
            PropertyNode newParent = requireNode(newParentId);
            // 环检测：新父是被拖节点自身或其后代 => 挂过去即成环，拒绝（验收必查项）
            if (closureMapper.countDescendantEdge(node.getId(), newParentId) > 0) {
                withContract(req.getContractNo(), () -> log.warn(
                        "房源归属树换父被拒绝（环状引用）nodeId={} newParentId={} requestNo={}",
                        node.getId(), newParentId, req.getRequestNo()));
                throw new IllegalArgumentException("不能挂到自身或自己的子孙节点下（环状引用）");
            }
            scopeGuard.requireCommunityInScope(newParent.getCommunity());
            scopeGuard.requireSelfOperated(newParent.getOperator());
            newCommunity = newParent.getCommunity();
        }

        int sortOrder = req.getSortOrder() == null ? 0 : req.getSortOrder();
        nodeMapper.updateParent(node.getId(), newParentId, sortOrder);
        if (newParentId == null) {
            // 挪为根：删干净旧祖先路径，仅留子树内部边（含自身行）
            closureMapper.deleteDetachedEdges(node.getId());
        } else {
            closureMapper.deleteDetachedEdges(node.getId());
            closureMapper.insertReattachedEdges(node.getId(), newParentId);
        }
        // 整子树（含自身）小区归属跟随：子孙自动跟随，对账归属不残留旧小区
        nodeMapper.updateSubtreeCommunity(node.getId(), newCommunity);

        PropertyNode refreshed = requireNode(node.getId());
        final String movedCommunity = newCommunity;
        withContract(req.getContractNo(), () -> log.info(
                "房源归属树换父成功 nodeId={} code={} newParentId={} newCommunity={} operator={} requestNo={}",
                node.getId(), node.getNodeCode(), newParentId, movedCommunity, operator, req.getRequestNo()));
        scopeGuard.audit("apartment:property-tree:manage", req.getRequestNo(),
                "action=MOVE,nodeId=" + node.getId() + ",newParentId=" + newParentId, null);
        return refreshed;
    }

    /** 同级排序：只改 sort_order，不动归属、不重建闭包。 */
    @Transactional(rollbackFor = Exception.class)
    public PropertyNode sort(NodeSortRequest req) {
        PropertyNode node = requireNode(req.getNodeId());
        scopeGuard.requireSelfOperated(node.getOperator());
        nodeMapper.updateSort(node.getId(), req.getSortOrder());
        withContract(req.getContractNo(), () -> log.info(
                "房源归属树同级排序 nodeId={} code={} parentId={} sortOrder={} requestNo={}",
                node.getId(), node.getNodeCode(), node.getParentId(), req.getSortOrder(), req.getRequestNo()));
        scopeGuard.audit("apartment:property-tree:manage", req.getRequestNo(),
                "action=SORT,nodeId=" + node.getId() + ",sortOrder=" + req.getSortOrder(), null);
        return requireNode(node.getId());
    }

    /** 子树查询（含根）：沿闭包表一次取全，按层、同父 sort_order 排，再叠加数据范围。 */
    public List<NodeView> subtree(Long rootId) {
        requireNode(rootId);
        List<NodeView> rows = closureMapper.selectSubtree(rootId);
        List<NodeView> visible = applyReadScope(rows);
        scopeGuard.audit("apartment:property-tree:view", null,
                "action=SUBTREE,rootId=" + rootId, visible.size());
        return visible;
    }

    /** 祖先链（含自身）：闭包表按 distance 倒序，返回“根 → … → 自己”的正确顺序。 */
    public List<NodeView> ancestors(Long nodeId) {
        requireNode(nodeId);
        List<NodeView> chain = closureMapper.selectAncestorChain(nodeId);
        // 链上节点跨小区/经多人经办：按最宽范围过滤可见节点；SELF 仅保留本人经办节点
        List<NodeView> visible = applyReadScope(chain);
        scopeGuard.audit("apartment:property-tree:view", null,
                "action=ANCESTORS,nodeId=" + nodeId, visible.size());
        return visible;
    }

    /** 同级节点（同父直接子节点），排序走节点表 sort_order；parentId 为空返回根列表。 */
    public List<PropertyNode> children(Long parentId) {
        List<PropertyNode> rows = parentId == null
                ? nodeMapper.selectRoots() : nodeMapper.selectChildren(parentId);
        List<PropertyNode> visible = new ArrayList<>();
        CurrentUser user = CurrentUserHolder.get();
        for (PropertyNode n : rows) {
            if (user == null || user.getScope().isAll()) {
                visible.add(n);
            } else if (user.getScope().isSelf()) {
                if (user.getUsername().equals(n.getOperator())) {
                    visible.add(n);
                }
            } else if (user.getScope().getCommunities().contains(n.getCommunity())) {
                visible.add(n);
            }
        }
        scopeGuard.audit("apartment:property-tree:view", null,
                "action=CHILDREN,parentId=" + parentId, visible.size());
        return visible;
    }

    private PropertyNode requireNode(Long id) {
        PropertyNode node = id == null ? null : nodeMapper.selectById(id);
        if (node == null) {
            throw new IllegalArgumentException("归属树节点不存在: " + id);
        }
        return node;
    }

    /** 读范围：SELF 仅本人经办；SCOPE 仅授权小区；ALL/无登录上下文全见。 */
    private List<NodeView> applyReadScope(List<NodeView> rows) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return rows;
        }
        List<NodeView> visible = new ArrayList<>();
        for (NodeView v : rows) {
            if (user.getScope().isSelf()) {
                if (user.getUsername().equals(v.getOperator())) {
                    visible.add(v);
                }
            } else if (user.getScope().getCommunities().contains(v.getCommunity())) {
                visible.add(v);
            }
        }
        return visible;
    }

    /**
     * 把合同号绑到日志上下文执行（建档/换父/排序日志可按合同号检索）。
     * 用 {@link TraceContext#contractScope} 强制收口：结束（含异常）自动还原进入前的值，
     * 不手写暂存/恢复，池化线程上漏还原就会把别人的合同号带进后续日志。
     */
    private void withContract(String contractNo, Runnable action) {
        try (TraceContext.Scope ignored = TraceContext.contractScope(
                StringUtils.hasText(contractNo) ? contractNo : null)) {
            action.run();
        }
    }
}
