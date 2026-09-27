package com.evops.apartment.tree.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 同级排序请求：只改某节点在同父下的 sort_order，不动归属（不重建闭包边）。
 * contractNo 可选：带上则本次排序日志按该合同号归集。
 */
public class NodeSortRequest {
    @NotNull(message = "节点 id 不能为空")
    private Long nodeId;
    @NotNull(message = "排序值不能为空")
    private Integer sortOrder;
    private String contractNo;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "操作人不能为空")
    private String operator;

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
