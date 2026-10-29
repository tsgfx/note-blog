package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.notesblog.dto.SearchResultDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索范围：公开入口只能看到已发布笔记。
 *
 * <p>这组测试守的是一条安全边界，不是排序偏好。分片索引按笔记建、不区分状态，
 * 草稿的正文同样在里面；游客能问答之后，只要有一次忘了过滤，
 * 匿名访客问一句就能把未发布正文问出来。
 */
class SemanticSearchScopeTest {

    private VectorIndex index;
    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        index = mock(VectorIndex.class);
        EmbeddingClient embedding = question -> List.of(1.0, 0.0, 0.0);
        service = new SemanticSearchService(embedding, index);
    }

    private static VectorIndex.Chunk chunk(long id, long noteId, String content, boolean published) {
        float[] vector = {1f, 0f, 0f};
        return new VectorIndex.Chunk(id, noteId, "标题" + noteId, "slug-" + noteId, null,
                content, content, vector, published);
    }

    @Test
    @DisplayName("公开检索不返回草稿")
    void publishedSearchExcludesDrafts() {
        VectorIndex.Chunk draft = chunk(1, 10, "草稿：数据库密码是 hunter2", false);
        VectorIndex.Chunk published = chunk(2, 20, "已发布：部署步骤", true);
        when(index.chunks()).thenReturn(List.of(draft, published));
        when(index.publishedChunks()).thenReturn(List.of(published));

        List<SearchResultDto> results = service.searchPublished("密码", 8);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).noteId()).isEqualTo(20L);
        assertThat(results).noneMatch(r -> r.content().contains("hunter2"));
    }

    @Test
    @DisplayName("管理员检索仍能看到草稿：这是有意的能力，不是漏网")
    void adminSearchIncludesDrafts() {
        VectorIndex.Chunk draft = chunk(1, 10, "草稿：数据库密码是 hunter2", false);
        when(index.chunks()).thenReturn(List.of(draft));

        List<SearchResultDto> results = service.search("密码", 8);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).noteId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("草稿与已发布内容相同的关键词，公开检索只给已发布的那条")
    void keywordRecallRespectsScope() {
        VectorIndex.Chunk draft = chunk(1, 10, "sm_120 的编译参数", false);
        VectorIndex.Chunk published = chunk(2, 20, "sm_120 的编译参数", true);
        when(index.chunks()).thenReturn(List.of(draft, published));
        when(index.publishedChunks()).thenReturn(List.of(published));

        List<SearchResultDto> results = service.searchPublished("sm_120", 8);

        assertThat(results).extracting(SearchResultDto::noteId).containsExactly(20L);
    }
}
