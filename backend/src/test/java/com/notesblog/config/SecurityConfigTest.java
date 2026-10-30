package com.notesblog.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.controller.AccountController;
import com.notesblog.controller.NoteController;
import com.notesblog.controller.PublicBlogController;
import com.notesblog.domain.NoteStatus;
import com.notesblog.repository.NoteRepository;
import com.notesblog.service.AuthService;
import com.notesblog.service.JwtService;
import com.notesblog.service.NoteService;
import com.notesblog.service.TokenVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 鉴权边界。这里守着三条容易回归的规则：
 * 无令牌必须 401、令牌版本不符必须 401、公开博客必须免认证。
 */
@WebMvcTest(controllers = {NoteController.class, AccountController.class, PublicBlogController.class})
@Import({SecurityConfig.class, JwtService.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private NoteService noteService;
    @MockitoBean
    private com.notesblog.service.NoteMetaService noteMetaService;
    @MockitoBean
    private com.notesblog.service.NoteMetaBatchService noteMetaBatchService;
    @MockitoBean
    private com.notesblog.service.RelatedNoteService relatedNoteService;
    @MockitoBean
    private com.notesblog.service.RecommendationService recommendationService;
    @MockitoBean
    private com.notesblog.service.BacklinkService backlinkService;
    @MockitoBean
    private com.notesblog.service.SearchAnalyticsService searchAnalyticsService;
    @MockitoBean
    private com.notesblog.service.PublishPreflightService publishPreflightService;
    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private NoteRepository noteRepository;
    @MockitoBean
    private TokenVersionService tokenVersionService;

    @TestConfiguration
    static class Props {
        @Bean
        SecurityProperties securityProperties() {
            return new SecurityProperties("test-secret-at-least-32-bytes-long-000000", 24, "admin", "pw");
        }
    }

    @BeforeEach
    void defaultVersionMatches() {
        when(tokenVersionService.isCurrent(anyString(), anyLong())).thenReturn(true);
    }

    private String token() {
        return jwtService.issue("admin", "ADMIN", 0L);
    }

    @Test
    @DisplayName("受保护接口无令牌：401 且返回统一结构")
    void requestWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/notes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    @DisplayName("有效令牌可以访问受保护接口")
    void requestWithValidTokenReachesController() throws Exception {
        mockMvc.perform(get("/api/notes").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("令牌版本号过期：401（改密码后旧令牌立即失效）")
    void staleTokenVersionIsRejected() throws Exception {
        when(tokenVersionService.isCurrent(anyString(), anyLong())).thenReturn(false);

        mockMvc.perform(get("/api/notes").header("Authorization", "Bearer " + token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("改密接口无令牌：401 —— 防止被误挂到 permitAll 的 /api/auth 下")
    void changePasswordRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/account/password")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"12345678\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("公开博客列表不需要令牌")
    void publicBlogIsAnonymous() throws Exception {
        when(noteRepository.findByStatus(any(NoteStatus.class), any()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/blog/posts"))
                .andExpect(status().isOk());
    }
}
