package com.evops.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.RbacProperties;
import com.evops.rbac.core.RbacUnauthorizedException;
import com.evops.rbac.entity.LoginToken;
import com.evops.rbac.entity.RbacUser;
import com.evops.rbac.mapper.LoginTokenMapper;
import com.evops.rbac.mapper.RbacUserMapper;
import com.evops.rbac.util.Passwords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * 登录令牌签发与校验。令牌为不透明随机串，只承载身份；
 * 授权每请求经 {@link AuthorizationService} 实时组装，令牌本身不缓存任何角色权限。
 */
@Service
public class LoginService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final RbacUserMapper userMapper;
    private final LoginTokenMapper tokenMapper;
    private final AuthorizationService authorizationService;
    private final RbacProperties properties;

    public LoginService(RbacUserMapper userMapper,
                        LoginTokenMapper tokenMapper,
                        AuthorizationService authorizationService,
                        RbacProperties properties) {
        this.userMapper = userMapper;
        this.tokenMapper = tokenMapper;
        this.authorizationService = authorizationService;
        this.properties = properties;
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginResult login(String username, String password) {
        RbacUser user = userMapper.selectOne(new QueryWrapper<RbacUser>().eq("username", username));
        if (user == null || !Passwords.matches(password, user.getPasswordHash())) {
            throw new RbacUnauthorizedException("用户名或口令错误");
        }
        if (!RbacUser.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new RbacUnauthorizedException("账号已停用");
        }
        LocalDateTime now = LocalDateTime.now();
        LoginToken token = new LoginToken();
        token.setToken(newToken());
        token.setUserId(user.getId());
        token.setIssuedAt(now);
        token.setExpiresAt(now.plusHours(properties.getTokenTtlHours()));
        token.setRevoked(false);
        token.setLastSeenAt(now);
        tokenMapper.insert(token);

        CurrentUser current = authorizationService.assemble(user.getId());
        return new LoginResult(token.getToken(), token.getExpiresAt(), current);
    }

    /** 令牌换身份：令牌撤销/过期/账号停用一律按未认证拒绝。 */
    public CurrentUser resolve(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new RbacUnauthorizedException("未登录");
        }
        LoginToken record = tokenMapper.selectOne(new QueryWrapper<LoginToken>().eq("token", token.trim()));
        if (record == null || Boolean.TRUE.equals(record.getRevoked())) {
            throw new RbacUnauthorizedException("登录已失效，请重新登录");
        }
        if (record.getExpiresAt() == null || record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RbacUnauthorizedException("登录已过期，请重新登录");
        }
        CurrentUser user = authorizationService.assemble(record.getUserId());
        if (user == null) {
            // 账号在令牌签发后被停用：当次请求即收权
            throw new RbacUnauthorizedException("账号已停用");
        }
        record.setLastSeenAt(LocalDateTime.now());
        tokenMapper.updateById(record);
        return user;
    }

    @Transactional(rollbackFor = Exception.class)
    public void logout(String token) {
        LoginToken record = tokenMapper.selectOne(new QueryWrapper<LoginToken>().eq("token", token));
        if (record != null && !Boolean.TRUE.equals(record.getRevoked())) {
            record.setRevoked(true);
            tokenMapper.updateById(record);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** 登录结果：令牌与当次身份快照（菜单/按钮并集、最宽数据范围）。 */
    public static class LoginResult {
        private final String token;
        private final LocalDateTime expiresAt;
        private final CurrentUser user;

        public LoginResult(String token, LocalDateTime expiresAt, CurrentUser user) {
            this.token = token;
            this.expiresAt = expiresAt;
            this.user = user;
        }

        public String getToken() { return token; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public CurrentUser getUser() { return user; }
    }
}
