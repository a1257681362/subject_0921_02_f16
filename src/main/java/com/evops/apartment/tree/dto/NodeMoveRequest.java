package com.evops.apartment.tree.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 拖拽换父请求：把 nodeId 挪到 newParentId 下，sortOrder 为新父下的同级次序。
 * newParentId 为空表示挪为根节点；挂到自身或自己子孙下构成环，服务层直接拒绝。
 * contractNo 可选：带上则本次维护日志按该合同号归集。
 */
public class NodeMoveRequest {
    @NotNull(message = "被拖动节点 id 不能为空")
    private Long nodeId;
    private Long newParentId;
    private Integer sortOrder;
    private String contractNo;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getNewParentId() { return newParentId; }
    public void setNewParentId(Long newParentId) { this.newParentId = newParentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
