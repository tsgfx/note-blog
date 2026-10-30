package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private VectorIndex vectorIndex;

    @Mock
    private NoteRepository noteRepository;

    @InjectMocks
    private RecommendationService service;

    private static Note note(long id, String slug, String title, String category, String... tags) {
        Note n = new Note();
        n.setId(id);
        n.setSlug(slug);
        n.setTitle(title);
        n.setStatus(NoteStatus.PUBLISHED);
        n.setSourcePath(category + "/" + slug + ".md");
        Set<Tag> tagSet = new java.util.HashSet<>();
        for (String t : tags) {
            Tag tag = new Tag();
            tag.setName(t);
            tagSet.add(tag);
        }
        n.setTags(tagSet);
        return n;
    }

    private static VectorIndex.Chunk chunk(long id, long noteId, float[] vector) {
        VectorIndex.normalize(vector);
        return new VectorIndex.Chunk(id, noteId, "t" + noteId, "slug-" + noteId, null, "c", "t c", vector, true);
    }

    @Test
    @DisplayName("冷启动推荐：无浏览足迹时推荐热门与精选文章，并进行类目打散")
    void coldStartReturnsPopularAndDiverseNotes() {
        Note n1 = note(1, "java-1", "Java 核心 1", "backend/java");
        n1.setViewCount(500);
        Note n2 = note(2, "java-2", "Java 核心 2", "backend/java");
        n2.setViewCount(400);
        Note n3 = note(3, "vue-1", "Vue 响应式底层", "frontend/vue");
        n3.setViewCount(350);

        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(n1, n2, n3)));

        List<NoteSummaryDto> recs = service.recommend(null, 5);

        assertThat(recs).hasSize(3);
        assertThat(recs.get(0).title()).isEqualTo("Java 核心 1");
        assertThat(recs.get(0).recommendReason()).contains("500 次阅读");
    }

    @Test
    @DisplayName("个性化推荐：基于历史阅读偏好进行向量与标签召回，排除已读")
    void personalizedRecommendationExcludesSeenAndBoostsInterestTags() {
        // 用户历史读过：MySQL 事务原理 (id: 10)
        Note historyNote = note(10, "mysql-tx", "MySQL 事务原理", "db/mysql", "MySQL", "数据库");
        when(noteRepository.findBySlug("mysql-tx")).thenReturn(Optional.of(historyNote));

        // 候选池
        Note candidate1 = note(11, "mysql-mvcc", "InnoDB MVCC 底层分析", "db/mysql", "MySQL", "并发");
        Note candidate2 = note(12, "python-nlp", "Python NLP 入门", "ai/nlp", "Python");

        when(noteRepository.findByStatus(eq(NoteStatus.PUBLISHED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(historyNote, candidate1, candidate2)));

        // 向量：history 与 candidate1 方向一致，与 candidate2 正交
        List<VectorIndex.Chunk> chunks = List.of(
                chunk(1, 10, new float[] {1f, 0f, 0f}),
                chunk(2, 11, new float[] {0.9f, 0.1f, 0f}),
                chunk(3, 12, new float[] {0f, 0f, 1f}));
        when(vectorIndex.chunks()).thenReturn(chunks);

        List<NoteSummaryDto> recs = service.recommend(List.of("mysql-tx"), 5);

        // 已读文章 10 必须被排除
        assertThat(recs).extracting(NoteSummaryDto::id).doesNotContain(10L);
        // candidate 11 同时命中向量与 MySQL 标签，排在首位
        assertThat(recs).isNotEmpty();
        assertThat(recs.get(0).id()).isEqualTo(11L);
        assertThat(recs.get(0).recommendReason()).contains("MySQL");
    }
}
