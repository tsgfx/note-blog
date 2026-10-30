package com.notesblog.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.controller.PublicBlogController;
import com.notesblog.domain.NoteStatus;
import com.notesblog.repository.NoteRepository;
import com.notesblog.service.AuthService;
import com.notesblog.service.JwtService;
import com.notesblog.service.TokenVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS 白名单。
 *
 * <p>这条测试的由来：容器化之后前端从 5173 换到 8088，白名单里没有 8088，
 * 浏览器登录直接 403（页面能打开、接口全挂）。当时的 curl 验证没带 Origin 头，
 * 所以一路绿灯——这里把浏览器行为固定下来。
 *
 * <p>后来又因为预览端口 4173 漏登记再吃一次 403，所以白名单改成「本机任意端口」通配。
 * 下面同时守着两条边界：本机新端口自动放行、外部来源仍然拒绝。
 */
@WebMvcTest(controllers = PublicBlogController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtService.class})
@TestPropertySource(
        properties =
                "app.cors.allowed-origins=http://localhost:[*],http://127.0.0.1:[*],http://192.168.1.7:8088")
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoteRepository noteRepository;
    @MockitoBean
    private com.notesblog.service.NoteService noteService;
    @MockitoBean
    private com.notesblog.service.RelatedNoteService relatedNoteService;
    @MockitoBean
    private com.notesblog.service.RecommendationService recommendationService;
    @MockitoBean
    private com.notesblog.service.BacklinkService backlinkService;
    @MockitoBean
    private com.notesblog.service.SearchAnalyticsService searchAnalyticsService;
    @MockitoBean
    private TokenVersionService tokenVersionService;
    @MockitoBean
    private AuthService authService;

    @org.springframework.boot.test.context.TestConfiguration
    static class Props {
        @org.springframework.context.annotation.Bean
        SecurityProperties securityProperties() {
            return new SecurityProperties("test-secret-at-least-32-bytes-long-000000", 24, "admin", "pw");
        }
    }

    @BeforeEach
    void setUp() {
        when(tokenVersionService.isCurrent(anyString(), anyLong())).thenReturn(true);
        when(noteRepository.findByStatus(any(NoteStatus.class), any())).thenReturn(Page.empty());
    }

    @Test
    @DisplayName("容器部署端口 8088 在白名单内")
    void containerOriginIsAllowed() throws Exception {
        mockMvc.perform(get("/api/blog/posts").header(HttpHeaders.ORIGIN, "http://localhost:8088"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8088"));
    }

    @Test
    @DisplayName("开发端口 5173 仍在白名单内")
    void devOriginIsAllowed() throws Exception {
        mockMvc.perform(get("/api/blog/posts").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未知来源被拒绝：403 Invalid CORS request")
    void foreignOriginIsRejected() throws Exception {
        mockMvc.perform(get("/api/blog/posts").header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("预览端口 4173 被放行（原先漏登记导致 403）")
    void previewOriginIsAllowed() throws Exception {
        mockMvc.perform(get("/api/blog/posts").header(HttpHeaders.ORIGIN, "http://127.0.0.1:4173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:4173"));
    }

    @Test
    @DisplayName("手机通过局域网 IP 访问：私有网段被放行（原先 403）")
    void lanOriginIsAllowed() throws Exception {
        mockMvc.perform(get("/api/blog/posts").header(HttpHeaders.ORIGIN, "http://192.168.1.7:8088"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://192.168.1.7:8088"));
    }

    @Test
    @DisplayName("通配只放开本机与私有网段：形似内网地址的外部域名仍然被拒")
    void lookalikeOriginIsRejected() throws Exception {
        mockMvc.perform(get("/api/blog/posts").header(HttpHeaders.ORIGIN, "http://localhost.evil.example:4173"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/blog/posts").header(HttpHeaders.ORIGIN, "http://192.168.evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("预检请求返回允许的方法")
    void preflightIsAnswered() throws Exception {
        mockMvc.perform(options("/api/blog/posts")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8088")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }
}
