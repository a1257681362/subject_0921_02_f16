package com.evops.apartment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 公寓楼交接班记录（apt-4，替代纸质交接本）。
 * 只增不删：登记后为 DRAFT 待确认，接班人确认后转 CONFIRMED；更正通过追加新记录说明。
 */
@TableName("t_handover_record")
public class HandoverRecord extends AuditEntity {
    public static final String SHIFT_MORNING = "MORNING";
    public static final String SHIFT_EVENING = "EVENING";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    private String community;
    private String building;
    private String shift;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate shiftDate;
    private String handoverBy;
    private String takeOverBy;
    private String pendingItems;
    private Integer keysCardCount;
    private BigDecimal cashAmount;
    private String content;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime confirmedAt;
    private String confirmRequestNo;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building; }
    public String getShift() { return shift; }
    public void setShift(String shift) { this.shift = shift; }
    public LocalDate getShiftDate() { return shiftDate; }
    public void setShiftDate(LocalDate shiftDate) { this.shiftDate = shiftDate; }
    public String getHandoverBy() { return handoverBy; }
    public void setHandoverBy(String handoverBy) { this.handoverBy = handoverBy; }
    public String getTakeOverBy() { return takeOverBy; }
    public void setTakeOverBy(String takeOverBy) { this.takeOverBy = takeOverBy; }
    public String getPendingItems() { return pendingItems; }
    public void setPendingItems(String pendingItems) { this.pendingItems = pendingItems; }
    public Integer getKeysCardCount() { return keysCardCount; }
    public void setKeysCardCount(Integer keysCardCount) { this.keysCardCount = keysCardCount; }
    public BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(BigDecimal cashAmount) { this.cashAmount = cashAmount; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getConfirmRequestNo() { return confirmRequestNo; }
    public void setConfirmRequestNo(String confirmRequestNo) { this.confirmRequestNo = confirmRequestNo; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
