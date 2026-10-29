package com.notesblog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** RSS 输出：上限可配、正文为空时不能整个 500。 */
@WebMvcTest(controllers = RssController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "site.base-url=http://notes.test/",
        "site.title=测试博客",
        "app.rss.max-items=2"
})
class RssControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoteRepository noteRepository;
    // 切片会扫到 JwtAuthFilter 与主启动类的 seedAdmin，即使关了过滤器也要满足它们的依赖
    @MockitoBean
    private com.notesblog.service.JwtService jwtService;
    @MockitoBean
    private com.notesblog.service.TokenVersionService tokenVersionService;
    @MockitoBean
    private com.notesblog.service.AuthService authService;

    private Note note(String title, String summary, String content) {
        Note note = new Note();
        note.setTitle(title);
        note.setSlug("s-" + title);
        note.setSummary(summary);
        note.setContentMd(content);
        note.setStatus(NoteStatus.PUBLISHED);
        com.notesblog.domain.Tag tag = new com.notesblog.domain.Tag();
        tag.setName("测试标签");
        note.setTags(java.util.Set.of(tag));
        return note;
    }

    @Test
    @DisplayName("正常输出 RSS，包含文章标签与无双斜杠的链接")
    void rendersFeed() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        List.of(note("标题一", "摘要一", "正文")), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/blog/rss.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/rss+xml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>测试博客</title>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<category>测试标签</category>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("http://notes.test/blog/s-标题一")));
    }

    @Test
    @DisplayName("正文与摘要都为空：用标题兜底，不抛 500")
    void blankContentFallsBackToTitle() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        List.of(note("只有标题", null, null)), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/blog/rss.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<description>只有标题</description>")));
    }

    @Test
    @DisplayName("条数上限由 app.rss.max-items 决定")
    void respectsMaxItems() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    // 断言控制器按配置的 2 条去查，而不是写死的 50
                    if (pageable.getPageSize() != 2) {
                        throw new AssertionError("期望每页 2 条，实际 " + pageable.getPageSize());
                    }
                    Page<Note> page = new PageImpl<>(List.of(), pageable, 0);
                    return page;
                });

        mockMvc.perform(get("/api/blog/rss.xml")).andExpect(status().isOk());
    }
}
