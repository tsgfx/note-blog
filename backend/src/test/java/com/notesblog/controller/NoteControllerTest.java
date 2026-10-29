package com.notesblog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.common.NotFoundException;
import com.notesblog.config.SecurityConfig;
import com.notesblog.config.SecurityProperties;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 参数错误必须收敛成 400，不能变成 500。
 * 注意：所有用例都要带合法令牌——Spring Security 过滤器在 DispatcherServlet 之前，
 * 无令牌会先拿到 401，把要断言的 400 掩盖掉。
 */
@WebMvcTest(controllers = NoteController.class)
@Import({SecurityConfig.class, JwtService.class})
class NoteControllerTest {

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
    private com.notesblog.service.PublishPreflightService publishPreflightService;
    @MockitoBean
    private TokenVersionService tokenVersionService;
    /** 主启动类里的 seedAdmin 也会被加载，需要这个依赖 */
    @MockitoBean
    private com.notesblog.service.AuthService authService;

    @TestConfiguration
    static class Props {
        @Bean
        SecurityProperties securityProperties() {
            return new SecurityProperties("test-secret-at-least-32-bytes-long-000000", 24, "admin", "pw");
        }
    }

    @BeforeEach
    void allowToken() {
        when(tokenVersionService.isCurrent(anyString(), anyLong())).thenReturn(true);
    }

    private String bearer() {
        return "Bearer " + jwtService.issue("admin", "ADMIN", 0L);
    }

    @Test
    @DisplayName("畸形 JSON：400 而不是 500")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/notes")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误，应为合法 JSON"));
    }

    @Test
    @DisplayName("非法枚举值：400 而不是 500")
    void illegalStatusEnumReturns400() throws Exception {
        mockMvc.perform(get("/api/notes?status=ARCHIVED").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    @DisplayName("非数字分页参数：400")
    void nonNumericPageReturns400() throws Exception {
        mockMvc.perform(get("/api/notes?page=abc").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("标题为空：400 且提示字段名")
    void blankTitleReturns400() throws Exception {
        mockMvc.perform(post("/api/notes")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"contentMd\":\"正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("title")));
    }

    @Test
    @DisplayName("发布不存在的笔记：404 透传")
    void publishMissingNoteReturns404() throws Exception {
        when(noteService.publish(anyLong())).thenThrow(new NotFoundException("笔记不存在：999"));

        mockMvc.perform(post("/api/notes/999/publish").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("正常列表返回统一响应体")
    void listReturnsApiResponse() throws Exception {
        when(noteService.list(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/notes").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("status / library / q 三个筛选条件透传到 service")
    void filtersArePassedThrough() throws Exception {
        when(noteService.list(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // 这里故意用不带转义的库名：MockMvc 不会对查询串做 URL 解码，
        // 用 "c%2B%2B" 会原样传下去，是测试工具的限制而非业务问题
        mockMvc.perform(get("/api/notes?status=DRAFT&library=docs&q=ajax")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(noteService).list(
                org.mockito.ArgumentMatchers.eq(com.notesblog.domain.NoteStatus.DRAFT),
                org.mockito.ArgumentMatchers.eq("docs"),
                org.mockito.ArgumentMatchers.eq("ajax"),
                any(),
                any());
    }

    @Test
    @DisplayName("page=-1 / size=0 不抛出 500")
    void invalidPagingFallsBackToDefaults() throws Exception {
        when(noteService.list(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/notes?page=-1&size=0").header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/libraries 命中统计接口，不会被 /{slug} 当成文章别名")
    void librariesRouteWinsOverPathVariable() throws Exception {
        when(noteService.libraries()).thenReturn(java.util.List.of(
                new com.notesblog.dto.LibraryCountDto("c++", 12)));

        mockMvc.perform(get("/api/notes/libraries").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("c++"))
                .andExpect(jsonPath("$.data[0].count").value(12));

        org.mockito.Mockito.verify(noteService, org.mockito.Mockito.never()).getBySlug(anyString());
    }

    @Test
    @DisplayName("批量发布空列表：400")
    void batchPublishEmptyIdsReturns400() throws Exception {
        mockMvc.perform(post("/api/notes/batch/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("请选择")));
    }

    @Test
    @DisplayName("批量发布超过 200 条：400")
    void batchPublishTooManyIdsReturns400() throws Exception {
        String ids = java.util.stream.LongStream.rangeClosed(1, 201)
                .mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(post("/api/notes/batch/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("200")));
    }

    @Test
    @DisplayName("/batch/publish 命中批量端点，不会被 /{id}/publish 抢走")
    void batchRouteWinsOverPathVariable() throws Exception {
        when(noteService.publishBatch(any())).thenReturn(
                new com.notesblog.dto.BatchResultDto(2, 2, 0, java.util.List.of()));

        mockMvc.perform(post("/api/notes/batch/publish")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(2));

        org.mockito.Mockito.verify(noteService).publishBatch(any());
        org.mockito.Mockito.verify(noteService, org.mockito.Mockito.never()).publish(anyLong());
    }

    @Test
    @DisplayName("GET /api/notes/tags 返回标签与篇数，用于左栏分类导航")
    void tagsAreExposed() throws Exception {
        when(noteService.tagFacets(any(), any())).thenReturn(java.util.List.of(
                new com.notesblog.dto.NoteTagCountDto("C++", 84),
                new com.notesblog.dto.NoteTagCountDto("链表", 17)));

        mockMvc.perform(get("/api/notes/tags?library=c%2B%2B").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("C++"))
                .andExpect(jsonPath("$.data[0].count").value(84))
                .andExpect(jsonPath("$.data[1].name").value("链表"));
    }

    @Test
    @DisplayName("多标签按 AND 传给服务层（同时带这两个标签才算命中）")
    void multipleTagsPassedThrough() throws Exception {
        when(noteService.list(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/notes?tag=链表&tag=力扣").header("Authorization", bearer()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(noteService).list(
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(java.util.List.of("链表", "力扣")),
                any());
    }

    @Test
    @DisplayName("GET /api/notes/folders 返回目录树，不会被 /{slug} 抢走路由")
    void foldersAreExposed() throws Exception {
        when(noteService.folders(any())).thenReturn(java.util.List.of(
                new com.notesblog.dto.FolderNodeDto("c++", "c++", 6,
                        java.util.List.of(new com.notesblog.dto.FolderNodeDto("项目A", "c++/项目A", 4,
                                java.util.List.of())))));

        mockMvc.perform(get("/api/notes/folders").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("c++"))
                .andExpect(jsonPath("$.data[0].count").value(6))
                .andExpect(jsonPath("$.data[0].children[0].path").value("c++/项目A"));
    }

    @Test
    @DisplayName("path 参数优先于旧名 library，两者都支持（旧书签不断）")
    void pathParamWins() throws Exception {
        when(noteService.list(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // 用 param() 而不是手写查询串：手写的 %2B%2F 不会被 MockMvc 解码，
        // 断言时拿到的是编码后的原文，看着像接口坏了
        mockMvc.perform(get("/api/notes")
                        .param("path", "c++/项目A")
                        .param("library", "c++")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(noteService).list(
                any(),
                org.mockito.ArgumentMatchers.eq("c++/项目A"),
                any(),
                any(),
                any());
    }
}
