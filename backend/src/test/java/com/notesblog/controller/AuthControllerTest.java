package com.notesblog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.dto.LoginResponse;
import com.notesblog.service.AuditService;
import com.notesblog.service.AuthService;
import com.notesblog.service.JwtService;
import com.notesblog.service.LoginAttemptService;
import com.notesblog.service.TokenVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 登录接口的频率限制。
 *
 * <p>这条测试守的是「密码不是唯一防线」：连续猜错要被挡在 429，
 * 而且锁定期间不再进入密码校验（否则限制形同虚设，照样能继续比对）。
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(LoginAttemptService.class)
class AuthControllerTest {

    private static final String BODY = """
            {"username":"admin","password":"wrong-password"}
            """;

    @Autowired
    private MockMvc mockMvc;

    /**
     * 限流器的状态是 Bean 里的实例字段，而测试上下文会被缓存复用。
     * 不在这里清一次的话，前一个用例把账号锁住，后一个用例一上来就 429。
     */
    @Autowired
    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void resetLimiter() {
        loginAttemptService.clear("admin", "127.0.0.1");
    }

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private AuditService auditService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private TokenVersionService tokenVersionService;

    private void expectRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("连续猜错 5 次后返回 429，并带上 Retry-After")
    void locksAfterRepeatedFailures() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("用户名或密码错误"));

        for (int i = 0; i < LoginAttemptService.MAX_PER_KEY; i++) {
            expectRejected();
        }

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value(429));

        // 被锁的请求不该再走到密码比对
        verify(authService, times(LoginAttemptService.MAX_PER_KEY)).login(any());
        // 只在刚触发锁定时记一条审计，不是每个被拒请求都记
        verify(auditService, times(1)).recordAs(anyString(), eq("LOGIN_BLOCKED"), isNull(), anyString());
    }

    @Test
    @DisplayName("登录成功返回令牌，且清空之前的失败计数")
    void successResetsFailures() throws Exception {
        when(authService.login(any()))
                .thenThrow(new BadCredentialsException("用户名或密码错误"))
                .thenThrow(new BadCredentialsException("用户名或密码错误"))
                .thenThrow(new BadCredentialsException("用户名或密码错误"))
                .thenThrow(new BadCredentialsException("用户名或密码错误"))
                .thenReturn(new LoginResponse("jwt-token", "admin", "管理员"));

        for (int i = 0; i < LoginAttemptService.MAX_PER_KEY - 1; i++) {
            expectRejected();
        }

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("jwt-token"));

        // 成功后计数归零：再错 4 次仍然只是 401，不该立刻被锁
        when(authService.login(any())).thenThrow(new BadCredentialsException("用户名或密码错误"));
        for (int i = 0; i < LoginAttemptService.MAX_PER_KEY - 1; i++) {
            expectRejected();
        }
    }
}
