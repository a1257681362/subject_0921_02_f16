package com.evops.rbac.web;

import javax.validation.constraints.NotBlank;

/**
 * 登录请求：账号 + 口令。
 */
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "口令不能为空")
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
