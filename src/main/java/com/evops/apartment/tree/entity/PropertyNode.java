package com.evops.apartment.tree.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.apartment.entity.AuditEntity;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 房源归属树节点。parent_id 记直接父（根为 NULL），sort_order 记同级顺序；
 * community 冗余自根节点，换父时整子树跟随更新，供 apt-3 数据范围 SCOPE 按小区过滤。
 */
@TableName("t_property_node")
public class PropertyNode extends AuditEntity {
    public static final String TYPE_COMMUNITY = "COMMUNITY";
    public static final String TYPE_BUILDING = "BUILDING";
    public static final String TYPE_FLOOR = "FLOOR";
    public static final String TYPE_ROOM = "ROOM";

    private String nodeCode;
    private String nodeName;
    private String nodeType;
    private String community;
    private Long parentId;
    private Integer sortOrder;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
