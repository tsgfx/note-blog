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
import java.time.LocalDateTime;
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

/** sitemap / robots：绝对地址拼得对、只暴露已发布内容。 */
@WebMvcTest(controllers = SitemapController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "site.base-url=http://notes.test/",
        "app.sitemap.max-items=2"
})
class SitemapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoteRepository noteRepository;
    @MockitoBean
    private com.notesblog.service.JwtService jwtService;
    @MockitoBean
    private com.notesblog.service.TokenVersionService tokenVersionService;
    @MockitoBean
    private com.notesblog.service.AuthService authService;

    private Note note(String slug) {
        Note note = new Note();
        note.setTitle(slug);
        note.setSlug(slug);
        note.setStatus(NoteStatus.PUBLISHED);
        note.setPublishedAt(LocalDateTime.of(2026, 10, 25, 20, 9, 43));
        note.setUpdatedAt(LocalDateTime.of(2026, 10, 26, 9, 0, 0));
        return note;
    }

    private void stub() {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        List.of(note("文章一"), note("文章二")), invocation.getArgument(1), 2));
    }

    @Test
    @DisplayName("输出 sitemap：首页、博客列表、每篇已发布笔记，且不出双斜杠")
    void rendersSitemap() throws Exception {
        stub();

        mockMvc.perform(get("/api/blog/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<loc>http://notes.test/</loc>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<loc>http://notes.test/blog</loc>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("http://notes.test/blog/文章一")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("notes.test//"))));
    }

    @Test
    @DisplayName("lastmod 取更新时间，格式是 yyyy-MM-dd")
    void usesUpdatedAtAsLastmod() throws Exception {
        stub();

        mockMvc.perform(get("/api/blog/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<lastmod>2026-10-26</lastmod>")));
    }

    @Test
    @DisplayName("只查已发布状态，条数上限走 app.sitemap.max-items")
    void queriesPublishedWithLimit() throws Exception {
        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    if (pageable.getPageSize() != 2) {
                        throw new AssertionError("期望每页 2 条，实际 " + pageable.getPageSize());
                    }
                    Page<Note> page = new PageImpl<>(List.of(), pageable, 0);
                    return page;
                });

        mockMvc.perform(get("/api/blog/sitemap.xml")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("robots.txt 带绝对 Sitemap 地址，并挡住管理面")
    void robotsPointsAtSitemap() throws Exception {
        mockMvc.perform(get("/api/blog/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sitemap: http://notes.test/sitemap.xml")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Disallow: /settings")));
    }
}
