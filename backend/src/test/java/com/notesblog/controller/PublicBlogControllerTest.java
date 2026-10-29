package com.notesblog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 公开文章列表的分页契约。
 *
 * <p>以前这里是写死的 {@code PageRequest.of(0, 100)}，发布超过 100 篇之后
 * 剩下的文章在博客里静默消失。这组测试把"真的在分页"固定下来。
 */
@WebMvcTest(controllers = PublicBlogController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicBlogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoteRepository noteRepository;
    // 切片会扫到 JwtAuthFilter 与主启动类的 seedAdmin
    @MockitoBean
    private com.notesblog.service.JwtService jwtService;
    @MockitoBean
    private com.notesblog.service.TokenVersionService tokenVersionService;
    @MockitoBean
    private com.notesblog.service.AuthService authService;

    private Note note(long id, String title) {
        Note note = new Note();
        note.setId(id);
        note.setTitle(title);
        note.setSlug("post-" + id);
        note.setStatus(NoteStatus.PUBLISHED);
        return note;
    }

    private Page<Note> pageOf(List<Note> notes, Pageable pageable, long total) {
        return new PageImpl<>(notes, pageable, total);
    }

    @Test
    @DisplayName("默认返回第 0 页、每页 20 条")
    void defaultPaging() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> pageOf(List.of(note(1, "一")), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/blog/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("一"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noteRepository).findByStatus(eq(NoteStatus.PUBLISHED), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("显式传 page/size 会透传到查询")
    void explicitPaging() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> pageOf(List.of(), invocation.getArgument(1), 0));

        mockMvc.perform(get("/api/blog/posts?page=2&size=5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noteRepository).findByStatus(eq(NoteStatus.PUBLISHED), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("size 超过上限被截到 50，避免一次拉走整库")
    void oversizedPageIsClamped() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> pageOf(List.of(), invocation.getArgument(1), 0));

        mockMvc.perform(get("/api/blog/posts?size=1000")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noteRepository).findByStatus(eq(NoteStatus.PUBLISHED), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("负数页码归零，不抛出异常")
    void negativePageFallsBackToZero() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> pageOf(List.of(), invocation.getArgument(1), 0));

        mockMvc.perform(get("/api/blog/posts?page=-3")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noteRepository).findByStatus(eq(NoteStatus.PUBLISHED), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("分页信息完整，前端才能画出翻页器")
    void responseCarriesPagingMetadata() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> pageOf(List.of(note(1, "一")), invocation.getArgument(1), 45));

        mockMvc.perform(get("/api/blog/posts?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(45))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.number").value(0));
    }

    @Test
    @DisplayName("首页统计只返回聚合数字，不含标题与正文")
    void statsOnlyExposesCounts() throws Exception {
        when(noteRepository.countByStatus(NoteStatus.PUBLISHED)).thenReturn(3L);
        when(noteRepository.countDistinctTagsByStatus(NoteStatus.PUBLISHED)).thenReturn(7L);
        when(noteRepository.findLatestPublishedAt(NoteStatus.PUBLISHED))
                .thenReturn(java.time.LocalDateTime.of(2026, 10, 1, 12, 0));

        mockMvc.perform(get("/api/blog/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(3))
                .andExpect(jsonPath("$.data.tags").value(7))
                .andExpect(jsonPath("$.data.latestPublished").exists())
                // 响应里不该出现任何正文/标题字段，防止以后有人顺手加进来
                .andExpect(jsonPath("$.data.title").doesNotExist())
                .andExpect(jsonPath("$.data.contentMd").doesNotExist());
    }

    @Test
    @DisplayName("统计不暴露笔记总数：草稿数量不能被匿名访客算出来")
    void statsDoesNotLeakTotalNoteCount() throws Exception {
        when(noteRepository.countByStatus(NoteStatus.PUBLISHED)).thenReturn(3L);
        when(noteRepository.countDistinctTagsByStatus(NoteStatus.PUBLISHED)).thenReturn(0L);
        when(noteRepository.findLatestPublishedAt(NoteStatus.PUBLISHED)).thenReturn(null);

        String body = mockMvc.perform(get("/api/blog/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // DTO 里没有 notes 字段；就算哪天有人把它加回来，响应里也不能出现"notes"键
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("\"notes\"");
    }

    @Test
    @DisplayName("一篇都没发布时统计不报错，latestPublished 为空")
    void statsToleratesEmptyLibrary() throws Exception {
        when(noteRepository.countByStatus(NoteStatus.PUBLISHED)).thenReturn(0L);
        when(noteRepository.countDistinctTagsByStatus(NoteStatus.PUBLISHED)).thenReturn(0L);
        when(noteRepository.findLatestPublishedAt(NoteStatus.PUBLISHED)).thenReturn(null);

        mockMvc.perform(get("/api/blog/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(0));
    }
}
