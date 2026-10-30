package com.notesblog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.dto.QuickCaptureRequest;
import com.notesblog.dto.QuickCaptureResponseDto;
import com.notesblog.service.InboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InboxController.class)
@AutoConfigureMockMvc(addFilters = false)
class InboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InboxService inboxService;

    // 切片会扫到 JwtAuthFilter 与主启动类的 seedAdmin
    @MockitoBean
    private com.notesblog.service.JwtService jwtService;
    @MockitoBean
    private com.notesblog.service.TokenVersionService tokenVersionService;
    @MockitoBean
    private com.notesblog.service.AuthService authService;

    @Test
    @DisplayName("剪藏未经授权：未提供有效 Token 时拒绝并返回 401")
    void unauthorizedWithoutToken() throws Exception {
        when(inboxService.isAuthorized(any(), any(), any(), any())).thenReturn(false);

        QuickCaptureRequest req = new QuickCaptureRequest("笔记内容", "iOS", List.of());
        mockMvc.perform(post("/api/inbox/quick-capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未授权")));
    }

    @Test
    @DisplayName("剪藏授权成功：校验通过并写入草稿库返回笔记元数据")
    void authorizedCaptureSuccess() throws Exception {
        when(inboxService.isAuthorized(any(), any(), any(), any())).thenReturn(true);
        when(inboxService.capture(any(), anyString())).thenReturn(new QuickCaptureResponseDto(
                55L,
                "测试标题",
                "inbox-slug-123",
                "收件箱/测试标题.md",
                List.of("测试"),
                LocalDateTime.now()
        ));

        QuickCaptureRequest req = new QuickCaptureRequest("测试标题\n内容", "测试来源", List.of("测试"));
        mockMvc.perform(post("/api/inbox/quick-capture")
                        .header("X-Inbox-Token", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(55))
                .andExpect(jsonPath("$.data.title").value("测试标题"));
    }

    @Test
    @DisplayName("获取剪藏配置：返回包含 Token 与示例的配置字典")
    void getInboxConfigReturnsDetails() throws Exception {
        when(inboxService.getConfig()).thenReturn(Map.of("token", "sec-123", "endpointUrl", "http://test/api/inbox/quick-capture"));

        mockMvc.perform(get("/api/admin/inbox/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("sec-123"));
    }
}
