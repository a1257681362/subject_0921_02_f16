package com.evops.apartment.tree.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.apartment.tree.entity.PropertyNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 归属树节点读写。同级排序、直接父子关系走节点表列；换父时整子树 community 跟随更新。
 */
@Mapper
public interface PropertyNodeMapper extends BaseMapper<PropertyNode> {

    /** 同一父下的直接子节点（同级），按 sort_order 升序、id 兜底稳定次序。 */
    @Select("SELECT * FROM t_property_node WHERE parent_id = #{parentId} " +
            "ORDER BY sort_order, id")
    List<PropertyNode> selectChildren(@Param("parentId") Long parentId);

    /** 全部根节点（parent_id IS NULL），按同级 sort_order 排。 */
    @Select("SELECT * FROM t_property_node WHERE parent_id IS NULL ORDER BY sort_order, id")
    List<PropertyNode> selectRoots();

    /** 换父：更新直接父与同级次序（闭包边由 PropertyClosureMapper 同事务重建）。 */
    @Update("UPDATE t_property_node SET parent_id = #{newParentId}, sort_order = #{sortOrder}, " +
            "data_version = data_version + 1, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{nodeId}")
    int updateParent(@Param("nodeId") Long nodeId,
                     @Param("newParentId") Long newParentId,
                     @Param("sortOrder") int sortOrder);

    /** 同级排序：只改 sort_order（不动归属），并递增数据版本。 */
    @Update("UPDATE t_property_node SET sort_order = #{sortOrder}, " +
            "data_version = data_version + 1, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{nodeId}")
    int updateSort(@Param("nodeId") Long nodeId, @Param("sortOrder") int sortOrder);

    /** 换父后整子树（含根自身）community 跟随新父归属，子孙在归属树内自动跟随。 */
    @Update("UPDATE t_property_node SET community = #{community}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id IN (SELECT descendant_id FROM t_property_closure WHERE ancestor_id = #{rootId})")
    int updateSubtreeCommunity(@Param("rootId") Long rootId, @Param("community") String community);
}
