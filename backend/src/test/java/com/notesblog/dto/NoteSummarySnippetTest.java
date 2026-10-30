package com.notesblog.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.notesblog.domain.Note;
import com.notesblog.domain.Tag;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 关键词命中片段的生成规则。
 *
 * <p>搜索同时匹配标题/摘要/正文/标签，但列表只显示摘要。命中正文时若不把那一句带出来，
 * 读者会看到一条"看起来对不上"的结果。
 */
class NoteSummarySnippetTest {

    private Note note(String title, String summary, String content) {
        Note n = new Note();
        n.setId(1L);
        n.setTitle(title);
        n.setSlug("s");
        n.setSummary(summary);
        n.setContentMd(content);
        n.setTags(Set.of());
        return n;
    }

    @Test
    @DisplayName("命中正文时返回关键词左右的上下文")
    void snippetWhenBodyMatches() {
        String prefix = "先聊清楚分层模型的边界，再谈每一层各自要解决的问题，".repeat(2);
        String body = prefix + "传输层负责端到端可靠交付，这是重点。";
        NoteSummaryDto dto = NoteSummaryDto.from(note("网络笔记", "讲讲分层", body), "端到端可靠交付");

        assertThat(dto.matchSnippet()).isNotNull();
        assertThat(dto.matchSnippet()).contains("端到端可靠交付");
        // 命中位置前面还有正文，所以要带前省略号；后面到文末就不加
        assertThat(dto.matchSnippet()).startsWith("…");
    }

    @Test
    @DisplayName("标题已显示命中位置时不再生成片段，避免同一句说两遍")
    void noSnippetWhenTitleMatches() {
        NoteSummaryDto dto = NoteSummaryDto.from(note("端到端可靠交付", null, "正文里也提到端到端可靠交付"), "端到端可靠交付");
        assertThat(dto.matchSnippet()).isNull();
    }

    @Test
    @DisplayName("摘要命中同样不再生成片段")
    void noSnippetWhenSummaryMatches() {
        NoteSummaryDto dto = NoteSummaryDto.from(note("标题", "关于端到端可靠交付", "正文"), "端到端可靠交付");
        assertThat(dto.matchSnippet()).isNull();
    }

    @Test
    @DisplayName("标签命中也不再生成片段")
    void noSnippetWhenTagMatches() {
        Note n = note("标题", "摘要", "正文里也有网络");
        Tag tag = new Tag();
        tag.setName("网络");
        n.setTags(Set.of(tag));

        assertThat(NoteSummaryDto.from(n, "网络").matchSnippet()).isNull();
    }

    @Test
    @DisplayName("没有关键词时字段为空，列表行为不变")
    void noKeywordNoSnippet() {
        assertThat(NoteSummaryDto.from(note("标题", "摘要", "正文")).matchSnippet()).isNull();
        assertThat(NoteSummaryDto.from(note("标题", "摘要", "正文"), "  ").matchSnippet()).isNull();
    }

    @Test
    @DisplayName("正文为空或未命中时返回 null，不抛异常")
    void toleratesEmptyContent() {
        assertThat(NoteSummaryDto.from(note("标题", "摘要", null), "关键词").matchSnippet()).isNull();
        assertThat(NoteSummaryDto.from(note("标题", "摘要", ""), "关键词").matchSnippet()).isNull();
        assertThat(NoteSummaryDto.from(note("标题", "摘要", "完全无关的内容"), "关键词").matchSnippet()).isNull();
    }

    @Test
    @DisplayName("片段去掉 Markdown 标记并压缩空白")
    void snippetStripsMarkdown() {
        String body = "## 标题\n\n这是一段**加粗**的说明，关键词在这里出现。\n\n- 列表项";
        String snippet = NoteSummaryDto.from(note("别的标题", null, body), "关键词").matchSnippet();

        assertThat(snippet).isNotNull();
        assertThat(snippet).doesNotContain("**").doesNotContain("##");
        assertThat(snippet).contains("关键词");
    }

    @Test
    @DisplayName("大小写不敏感：按小写关键词也能命中英文正文")
    void caseInsensitiveMatch() {
        String snippet = NoteSummaryDto.from(note("标题", null, "Use Vector Search to find notes"), "vector search")
                .matchSnippet();
        assertThat(snippet).contains("Vector Search");
    }
}
