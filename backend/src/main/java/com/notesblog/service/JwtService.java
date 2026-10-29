package com.notesblog.service;

import com.notesblog.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtService {
    private final SecurityProperties properties;

    public String issue(String username, String role, long tokenVersion) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        // tv = token version，与 users.token_version 比对，改密码后旧令牌即失效
        claims.put("tv", tokenVersion);
        return Jwts.builder().subject(username).claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.jwtExpirationHours() * 3600)))
                .signWith(key()).compact();
    }

    /**
     * 校验令牌并取出身份。只解析一次，避免 valid() + username() + role() 各解析一遍。
     * 令牌非法、过期或签名不符时返回空，由调用方决定如何处理。
     */
    public Optional<Principal> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload();
            Object role = claims.get("role");
            Object version = claims.get("tv");
            return Optional.of(new Principal(claims.getSubject(),
                    role == null ? "ADMIN" : role.toString(),
                    // 升级前签发的令牌没有 tv，按 0 处理，正好等于 ALTER 的列默认值
                    version instanceof Number number ? number.longValue() : 0L));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 令牌中的身份：用户名 + 角色名（不含 ROLE_ 前缀）+ 令牌版本号。 */
    public record Principal(String username, String role, long tokenVersion) {
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }
}
