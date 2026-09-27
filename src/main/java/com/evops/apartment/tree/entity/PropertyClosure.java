package com.evops.apartment.tree.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 房源归属闭包表一行：ancestor 是 descendant 的祖先（distance 代际距离，0 为自身）。
 * 复合主键 (ancestor_id, descendant_id)；只描述归属关系，不带业务审计字段。
 */
@TableName("t_property_closure")
public class PropertyClosure {
    private Long ancestorId;
    private Long descendantId;
    private Integer distance;

    public Long getAncestorId() { return ancestorId; }
    public void setAncestorId(Long ancestorId) { this.ancestorId = ancestorId; }
    public Long getDescendantId() { return descendantId; }
    public void setDescendantId(Long descendantId) { this.descendantId = descendantId; }
    public Integer getDistance() { return distance; }
    public void setDistance(Integer distance) { this.distance = distance; }
}
