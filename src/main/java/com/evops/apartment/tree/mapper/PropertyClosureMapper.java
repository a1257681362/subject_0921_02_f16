package com.evops.apartment.tree.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.apartment.tree.dto.NodeView;
import com.evops.apartment.tree.entity.PropertyClosure;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 房源归属闭包表读写。所有树形查询走表结构（等值/联表），不做递归：
 *
 * - 子树：ancestor_id = 根，JOIN 节点表一次取全子树；
 * - 祖先链：descendant_id = 节点，按 distance 倒序即“根 → 自己”；
 * - 换父：标准闭包表“删旧路径 × 接新路径”两条集合语句，子孙边自动跟随；
 * - 环检测：(ancestor=被拖节点, descendant=目标父) 有行即目标在其子树内，拒绝。
 */
@Mapper
public interface PropertyClosureMapper extends BaseMapper<PropertyClosure> {

    /** 新节点自身行 distance=0。 */
    @Insert("INSERT INTO t_property_closure (ancestor_id, descendant_id, distance) " +
            "VALUES (#{nodeId}, #{nodeId}, 0)")
    int insertSelfEdge(@Param("nodeId") Long nodeId);

    /**
     * 新节点挂到父下：复制父的全部祖先边（含父自身行），distance 全部 +1。
     * 与自身行一起构成新节点的完整祖先链。
     */
    @Insert("INSERT INTO t_property_closure (ancestor_id, descendant_id, distance) " +
            "SELECT ancestor_id, #{nodeId}, distance + 1 FROM t_property_closure " +
            "WHERE descendant_id = #{parentId}")
    int insertInheritedEdges(@Param("nodeId") Long nodeId, @Param("parentId") Long parentId);

    /**
     * 换父第 1 步：删除“被拖子树 ↔ 原子树上层”之间的路径。
     * 保留子树内部边（ancestor 落在子树内的行）；子树成员 = ancestor=nodeId 的全部 descendant。
     */
    @Delete("DELETE FROM t_property_closure " +
            "WHERE descendant_id IN (SELECT descendant_id FROM t_property_closure WHERE ancestor_id = #{nodeId}) " +
            "AND ancestor_id NOT IN (SELECT descendant_id FROM t_property_closure WHERE ancestor_id = #{nodeId})")
    int deleteDetachedEdges(@Param("nodeId") Long nodeId);

    /**
     * 换父第 2 步：接入新父。新父的每个祖先（含新父自身）到被拖节点的每个后代
     * （含节点自身）各加一行，distance = 祖先到新父 + 新父到节点(1) + 节点到后代。
     */
    @Insert("INSERT INTO t_property_closure (ancestor_id, descendant_id, distance) " +
            "SELECT super.ancestor_id, sub.descendant_id, super.distance + 1 + sub.distance " +
            "FROM t_property_closure super CROSS JOIN t_property_closure sub " +
            "WHERE super.descendant_id = #{newParentId} AND sub.ancestor_id = #{nodeId}")
    int insertReattachedEdges(@Param("nodeId") Long nodeId, @Param("newParentId") Long newParentId);

    /**
     * 环检测：candidate 是否为 nodeId 自身或其后代。
     * 返回 >0 即把 nodeId 挂到 candidate 下会成环（挂自身/挂子孙），业务层直接拒绝。
     */
    @Select("SELECT COUNT(*) FROM t_property_closure " +
            "WHERE ancestor_id = #{nodeId} AND descendant_id = #{candidateId}")
    long countDescendantEdge(@Param("nodeId") Long nodeId, @Param("candidateId") Long candidateId);

    /** 子树全量节点（含根自身）：distance 为距根子树层，先按层、再按同父 sort_order 排。 */
    @Select("SELECT n.id, n.node_code, n.node_name, n.node_type, n.community, n.parent_id, " +
            "n.sort_order, n.operator, c.distance " +
            "FROM t_property_closure c JOIN t_property_node n ON n.id = c.descendant_id " +
            "WHERE c.ancestor_id = #{rootId} " +
            "ORDER BY c.distance, n.parent_id, n.sort_order, n.id")
    List<NodeView> selectSubtree(@Param("rootId") Long rootId);

    /**
     * 祖先链节点（含自身）：按 distance 倒序——最远的根排第一、自己排最后，
     * 即验收要求的“根 → … → 自己”正确顺序。
     */
    @Select("SELECT n.id, n.node_code, n.node_name, n.node_type, n.community, n.parent_id, " +
            "n.sort_order, n.operator, c.distance " +
            "FROM t_property_closure c JOIN t_property_node n ON n.id = c.ancestor_id " +
            "WHERE c.descendant_id = #{nodeId} " +
            "ORDER BY c.distance DESC, n.id")
    List<NodeView> selectAncestorChain(@Param("nodeId") Long nodeId);
}
