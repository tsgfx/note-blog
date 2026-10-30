package com.notesblog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.dto.SearchResultDto;
import com.notesblog.service.GuestQuotaService;
import com.notesblog.service.LlmStreamingClient;
import com.notesblog.service.SemanticSearchService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = AskController.class)
@AutoConfigureMockMvc(addFilters = false)
class AskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticSearchService searchService;
    @MockitoBean
    private LlmStreamingClient llmClient;
    @MockitoBean
    private GuestQuotaService guestQuota;

    @MockitoBean
    private com.notesblog.service.JwtService jwtService;
    @MockitoBean
    private com.notesblog.service.TokenVersionService tokenVersionService;
    @MockitoBean
    private com.notesblog.service.AuthService authService;

    @Test
    @DisplayName("游客配额用尽：直接返回 429 与 Retry-After，不进入检索也不建流")
    void guestQuotaExhaustedReturns429() throws Exception {
        when(guestQuota.consume(any())).thenReturn(new GuestQuotaService.Decision(false, 0, 3600L));

        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"TCP 三次握手过程\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("今日提问次数已用完，明天再来"));

        verify(searchService, never()).searchPublished(anyString(), anyInt());
        verify(llmClient, never()).stream(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("游客未检索到公开相关内容：直接输出兜底文案并完成，不调 LLM 避免幻觉与烧 token")
    void guestNoMatchingSourcesReturnsFallbackWithoutLlm() throws Exception {
        when(guestQuota.consume(any())).thenReturn(new GuestQuotaService.Decision(true, 4, 0L));
        when(searchService.searchPublished(eq("无相关的稀奇古怪问题"), anyInt())).thenReturn(List.of());

        MvcResult mvcResult = mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"无相关的稀奇古怪问题\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String responseContent = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(responseContent).contains("笔记库中未检索到与该问题相关的公开内容。");
        assertThat(responseContent).contains("event:done");

        verify(llmClient, never()).stream(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("管理员问答：走全库检索（含草稿），不扣减游客配额")
    void adminUsesFullSearchAndSkipsGuestQuota() throws Exception {
        when(searchService.search(eq("草稿内容探查"), anyInt())).thenReturn(List.of(
                new SearchResultDto(10L, "私密架构草案", "arch-draft", "核心模块", "鉴权与网关", 0.85)
        ));

        org.springframework.security.core.Authentication auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "admin", "pw", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));

        MvcResult mvcResult = mockMvc.perform(post("/api/ask")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"草稿内容探查\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        verify(guestQuota, never()).consume(any());
        verify(searchService).search(eq("草稿内容探查"), eq(8));
        verify(searchService, never()).searchPublished(anyString(), anyInt());
        verify(llmClient).stream(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("管理员未检索到相关内容：输出管理员版兜底文案并直接完成，不调 LLM")
    void adminNoMatchingSourcesReturnsFallbackWithoutLlm() throws Exception {
        when(searchService.search(eq("不存在的秘密"), anyInt())).thenReturn(List.of());

        org.springframework.security.core.Authentication auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "admin", "pw", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));

        MvcResult mvcResult = mockMvc.perform(post("/api/ask")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"不存在的秘密\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String responseContent = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(responseContent).contains("笔记库中未检索到与该问题相关的内容。");
        assertThat(responseContent).contains("event:done");

        verify(llmClient, never()).stream(anyString(), anyString(), any());
    }
}
