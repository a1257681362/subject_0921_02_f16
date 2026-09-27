package com.evops.apartment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 押金扣除明细：每个扣除项一行，逐条留痕。
 */
@TableName("t_deposit_deduction")
public class DepositDeduction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long checkoutId;
    private String item;
    private BigDecimal amount;
    private String remark;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCheckoutId() { return checkoutId; }
    public void setCheckoutId(Long checkoutId) { this.checkoutId = checkoutId; }
    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
