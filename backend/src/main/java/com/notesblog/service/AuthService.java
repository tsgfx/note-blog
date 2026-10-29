package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.config.SecurityProperties;
import com.notesblog.domain.User;
import com.notesblog.dto.ChangePasswordRequest;
import com.notesblog.dto.LoginRequest;
import com.notesblog.dto.LoginResponse;
import com.notesblog.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityProperties properties;
    private final AuditService auditService;
    private final TokenVersionService tokenVersionService;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 只记录尝试的用户名，绝不记录密码
            auditService.recordAs(request.username(), "LOGIN_FAILED", null, null);
            throw new BadCredentialsException("用户名或密码错误");
        }
        auditService.recordAs(user.getUsername(), "LOGIN", null, null);
        return new LoginResponse(jwtService.issue(user.getUsername(), user.getRole(), user.getTokenVersion()),
                user.getUsername(), user.getDisplayName());
    }

    public void ensureAdmin() {
        if (userRepository.findByUsername(properties.adminUsername()).isPresent()) return;
        User user = new User();
        user.setUsername(properties.adminUsername());
        user.setDisplayName("管理员");
        user.setRole("ADMIN");
        user.setPasswordHash(passwordEncoder.encode(properties.adminPassword()));
        userRepository.save(user);
    }

    /**
     * 修改密码。成功后把令牌版本号 +1，该用户此前签发的所有 JWT 立即失效。
     *
     * <p>旧密码错误抛 IllegalArgumentException（400）而不是 BadCredentialsException（401）：
     * 前端的 401 拦截器会清 token 并跳登录页，改密时输错一次就被登出属于误伤。
     */
    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("用户不存在：" + username));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        PasswordPolicy.validate(request.newPassword());
        // 用编码器比对而不是字符串比较：能识别"不同输入但编码后相同"的边角，且与登录口径一致
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        tokenVersionService.revokeAll(username);
        auditService.record("PASSWORD_CHANGE", username, null);
    }
}
