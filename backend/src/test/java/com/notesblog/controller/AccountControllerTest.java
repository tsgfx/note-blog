package com.notesblog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.config.SecurityConfig;
import com.notesblog.config.SecurityProperties;
import com.notesblog.dto.MeResponse;
import com.notesblog.service.AuthService;
import com.notesblog.service.JwtService;
import com.notesblog.service.TokenVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/account/me}：前端靠它判断「我是不是管理员」。
 *
 * <p>这里守两条边界：无令牌 401；**非管理员也必须 200**（如果谁把它改成
 * {@code hasRole("ADMIN")}，前端就永远拿不到「我是访客」，只能看到 403）。
 */
@WebMvcTest(controllers = AccountController.class)
@Import({SecurityConfig.class, JwtService.class})
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private TokenVersionService tokenVersionService;

    @TestConfiguration
    static class Props {
        @Bean
        SecurityProperties securityProperties() {
            return new SecurityProperties("test-secret-at-least-32-bytes-long-000000", 24, "admin", "pw");
        }
    }

    @Test
    @DisplayName("无令牌：401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("管理员令牌：200，角色回 ADMIN")
    void adminSeesOwnRole() throws Exception {
        when(tokenVersionService.isCurrent("admin", 0L)).thenReturn(true);
        when(authService.me(eq("admin"), any())).thenReturn(new MeResponse("admin", "郭瑞超", "ADMIN"));

        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + jwtService.issue("admin", "ADMIN", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.displayName").value("郭瑞超"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("非管理员令牌：仍然 200，角色回 VIEWER —— 前端要能自己判断「不是管理员」")
    void viewerGetsOwnRoleInsteadOfForbidden() throws Exception {
        when(tokenVersionService.isCurrent("viewer", 0L)).thenReturn(true);
        when(authService.me(eq("viewer"), any())).thenReturn(new MeResponse("viewer", null, "VIEWER"));

        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + jwtService.issue("viewer", "VIEWER", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("VIEWER"));
    }

    @Test
    @DisplayName("小写角色在令牌里被大写：回 ADMIN —— 前后端判定口径一致")
    void lowercaseRoleIsNormalised() throws Exception {
        when(tokenVersionService.isCurrent("admin", 0L)).thenReturn(true);
        when(authService.me(eq("admin"), any())).thenReturn(new MeResponse("admin", "郭瑞超", "ADMIN"));

        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + jwtService.issue("admin", "admin", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }
}
