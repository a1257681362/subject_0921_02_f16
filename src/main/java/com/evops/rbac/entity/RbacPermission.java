package com.evops.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 权限点：MENU 菜单 / BUTTON 按钮（查看、导出等动作级权限也归 BUTTON）。
 */
@TableName("t_rbac_permission")
public class RbacPermission {
    public static final String TYPE_MENU = "MENU";
    public static final String TYPE_BUTTON = "BUTTON";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String permCode;
    private String permName;
    private String permType;
    private String module;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPermCode() { return permCode; }
    public void setPermCode(String permCode) { this.permCode = permCode; }
    public String getPermName() { return permName; }
    public void setPermName(String permName) { this.permName = permName; }
    public String getPermType() { return permType; }
    public void setPermType(String permType) { this.permType = permType; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
