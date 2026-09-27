package com.evops.apartment.tree.dto;

import javax.validation.constraints.NotBlank;

/**
 * 归属树节点新增请求。parentId 为空表示建根节点；
 * 根节点必须给 community，子节点 community 默认继承父（换父时整子树跟随）。
 * contractNo 可选：带上则本次维护日志按该合同号归集（管家对账按合同号查日志）。
 */
public class NodeCreateRequest {
    @NotBlank(message = "节点编号不能为空")
    private String nodeCode;
    @NotBlank(message = "节点名称不能为空")
    private String nodeName;
    private String nodeType;
    private String community;
    private Long parentId;
    private Integer sortOrder;
    private String contractNo;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

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
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
