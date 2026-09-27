package com.evops.apartment.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 押金扣除项：逐条留痕的最小单元。
 */
public class DeductionItemRequest {
    @NotBlank(message = "扣除项目不能为空")
    private String item;
    @NotNull(message = "扣除金额不能为空")
    @DecimalMin(value = "0.00", message = "扣除金额不能为负")
    private BigDecimal amount;
    private String remark;

    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
