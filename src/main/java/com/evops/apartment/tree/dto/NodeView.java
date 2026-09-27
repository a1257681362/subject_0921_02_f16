package com.evops.apartment.tree.dto;

/**
 * 归属树查询视图：节点字段 + distance（沿闭包表联表得到的代际距离）。
 * 子树查询时 distance=距子树根的层数（根为 0）；祖先链查询时 distance=距所查节点的层数
 * （父为 1，根最远），服务层按 distance 倒序返回“根 → 自己”的祖先链。
 */
public class NodeView {
    private Long id;
    private String nodeCode;
    private String nodeName;
    private String nodeType;
    private String community;
    private Long parentId;
    private Integer sortOrder;
    private Integer distance;
    private String operator;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public Integer getDistance() { return distance; }
    public void setDistance(Integer distance) { this.distance = distance; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
