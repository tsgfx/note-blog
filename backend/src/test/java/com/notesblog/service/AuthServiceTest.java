package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.config.SecurityProperties;
import com.notesblog.domain.User;
import com.notesblog.dto.ChangePasswordRequest;
import com.notesblog.dto.LoginRequest;
import com.notesblog.dto.LoginResponse;
import com.notesblog.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 改密的校验顺序与副作用。纯单元测试。 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String CURRENT = "change-me-now";

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuditService auditService;
    @Mock
    private TokenVersionService tokenVersionService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AuthService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, passwordEncoder, jwtService,
                new SecurityProperties("test-secret-at-least-32-bytes-long-000000", 24, "admin", "pw"),
                auditService, tokenVersionService);
        admin = new User();
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        admin.setPasswordHash(passwordEncoder.encode(CURRENT));
    }

    private ChangePasswordRequest request(String current, String next) {
        return new ChangePasswordRequest(current, next);
    }

    @Test
    @DisplayName("旧密码错误：拒绝且不改库、不吊销令牌")
    void wrongCurrentPasswordRejected() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.changePassword("admin", request("wrong", "newpass-123")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前密码不正确");

        verify(userRepository, never()).save(any());
        verify(tokenVersionService, never()).revokeAll(anyString());
    }

    @Test
    @DisplayName("新密码不足 8 位：拒绝")
    void shortNewPasswordRejected() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.changePassword("admin", request(CURRENT, "1234567")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少 8 位");

        verify(tokenVersionService, never()).revokeAll(anyString());
    }

    @Test
    @DisplayName("新旧密码相同：拒绝且不吊销令牌")
    void samePasswordRejected() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.changePassword("admin", request(CURRENT, CURRENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能与当前密码相同");

        verify(tokenVersionService, never()).revokeAll(anyString());
    }

    @Test
    @DisplayName("新密码超过 72 字节：拒绝（30 个汉字 = 90 字节）")
    void tooLongNewPasswordRejected() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        String chinese = "密码密码密码密码密码密码密码密码密码密码密码密码密码密码密码";

        assertThatThrownBy(() -> service.changePassword("admin", request(CURRENT, chinese)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    @Test
    @DisplayName("正常改密：重哈希、吊销令牌、写审计各一次")
    void successRehashesAndRevokes() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        String next = "brand-new-pass";

        service.changePassword("admin", request(CURRENT, next));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(passwordEncoder.matches(next, saved.getValue().getPasswordHash())).isTrue();
        verify(tokenVersionService).revokeAll("admin");
        verify(auditService).record("PASSWORD_CHANGE", "admin", null);
    }

    @Test
    @DisplayName("登录用库里的令牌版本号签发")
    void loginIssuesTokenWithStoredVersion() {
        admin.setTokenVersion(7L);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(jwtService.issue("admin", "ADMIN", 7L)).thenReturn("token");

        LoginResponse response = service.login(new LoginRequest("admin", CURRENT));

        assertThat(response.token()).isEqualTo("token");
    }
}
