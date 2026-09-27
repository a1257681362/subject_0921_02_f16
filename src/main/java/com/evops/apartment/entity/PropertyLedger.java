package com.evops.apartment.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/**
 * 房源台账流水。只增不改：登记为 REGISTER，更正通过 REVERSE 冲销单完成。
 */
@TableName("t_property_ledger")
public class PropertyLedger extends AuditEntity {
    public static final String TYPE_REGISTER = "REGISTER";
    public static final String TYPE_REVERSE = "REVERSE";

    private String propertyCode;
    private String community;
    private String building;
    private String roomNo;
    private BigDecimal area;
    private String entryType;
    private Long refEntryId;
    private String remark;

    public String getPropertyCode() { return propertyCode; }
    public void setPropertyCode(String propertyCode) { this.propertyCode = propertyCode; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building; }
    public String getRoomNo() { return roomNo; }
    public void setRoomNo(String roomNo) { this.roomNo = roomNo; }
    public BigDecimal getArea() { return area; }
    public void setArea(BigDecimal area) { this.area = area; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public Long getRefEntryId() { return refEntryId; }
    public void setRefEntryId(Long refEntryId) { this.refEntryId = refEntryId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
