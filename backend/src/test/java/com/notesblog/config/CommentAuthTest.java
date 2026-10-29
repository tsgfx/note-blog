package com.notesblog.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.controller.AdminCommentController;
import com.notesblog.controller.CommentController;
import com.notesblog.domain.CommentStatus;
import com.notesblog.service.AuthService;
import com.notesblog.service.CommentService;
import com.notesblog.service.JwtService;
import com.notesblog.service.RateLimiter;
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
 * 评论的鉴权边界与管理动作。
 *
 * <p>要守住的两条：
 * <ul>
 *   <li>游客能读能写评论（{@code /api/blog/**} 免认证）</li>
 *   <li>管理动作必须登录（{@code /api/admin/**} 走默认鉴权）——
 *       一旦有人把管理接口挪到 {@code /api/blog} 下，隐藏/删除就变成匿名可调</li>
 * </ul>
 */
@WebMvcTest(controllers = {CommentController.class, AdminCommentController.class})
@Import({SecurityConfig.class, JwtService.class})
class CommentAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private CommentService commentService;
    @MockitoBean
    private RateLimiter rateLimiter;
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

    @BeforeEach
    void allowAll() {
        when(tokenVersionService.isCurrent(anyString(), anyLong())).thenReturn(true);
        when(rateLimiter.acquire(anyString(), anyInt(), any()))
                .thenReturn(new RateLimiter.Decision(true, 0));
        when(commentService.publicList(anyString(), anyInt(), anyInt())).thenReturn(Page.empty());
    }

    private String adminToken() {
        return jwtService.issue("admin", "ADMIN", 0L);
    }

    @Test
    @DisplayName("游客能读评论列表：不需要令牌")
    void guestCanListComments() throws Exception {
        mockMvc.perform(get("/api/blog/posts/hello/comments"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("游客能发评论：不需要令牌")
    void guestCanPostComment() throws Exception {
        mockMvc.perform(post("/api/blog/posts/hello/comments")
                        .contentType("application/json")
                        .content("{\"content\":\"写得不错\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("蜜罐被填：仍然 200，且不占限流额度")
    void honeypotReturnsOkWithoutRateLimit() throws Exception {
        mockMvc.perform(post("/api/blog/posts/hello/comments")
                        .contentType("application/json")
                        .content("{\"content\":\"spam\",\"website\":true}"))
                .andExpect(status().isOk());

        // 机器人不该消耗真实访客的额度
        verify(rateLimiter, never()).acquire(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("限流命中：429 并带 Retry-After")
    void rateLimitedReturns429() throws Exception {
        when(rateLimiter.acquire(anyString(), anyInt(), any()))
                .thenReturn(new RateLimiter.Decision(false, 42));

        mockMvc.perform(post("/api/blog/posts/hello/comments")
                        .contentType("application/json")
                        .content("{\"content\":\"再来一条\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    @DisplayName("管理端评论列表无令牌：401")
    void adminListRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/admin/comments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("隐藏评论无令牌：401 —— 否则任何人都能把评论下架")
    void adminVisibilityRequiresAuth() throws Exception {
        mockMvc.perform(patch("/api/admin/comments/1/visibility")
                        .contentType("application/json")
                        .content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("管理员能隐藏评论")
    void adminCanHide() throws Exception {
        mockMvc.perform(patch("/api/admin/comments/1/visibility")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isOk());

        verify(commentService).setStatus(eq(1L), eq(CommentStatus.HIDDEN));
    }
}
