package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 相关笔记推荐的打分规则。
 *
 * <p>核心断言只有三条：自己不会推荐自己；没有索引（没分片）就什么都不推；
 * 相似度低于阈值的宁可不推，也不塞「看起来相关」的噪音。
 */
@ExtendWith(MockitoExtension.class)
class RelatedNoteServiceTest {

    @Mock
    private VectorIndex index;
    @Mock
    private NoteRepository noteRepository;

    private RelatedNoteService service;

    @BeforeEach
    void setUp() {
        // 必须在 mock 注入之后构造；字段初始化时 index 还是 null
        service = new RelatedNoteService(index, noteRepository);
    }

    private static VectorIndex.Chunk chunk(long id, long noteId, float[] vector) {
        VectorIndex.normalize(vector);
        // 这些用例只验相关笔记的相似度计算，与发布状态无关，一律标已发布
        return new VectorIndex.Chunk(id, noteId, "t" + noteId, "slug-" + noteId, null, "c", "t c",
                vector, true);
    }

    private static Note note(long id, String title) {
        Note note = new Note();
        note.setId(id);
        note.setTitle(title);
        note.setSlug("slug-" + id);
        note.setSummary(title + "的摘要");
        return note;
    }

    @Test
    @DisplayName("按「笔记向量均值 × 对方最强分片」打分，排除自己与已删笔记")
    void ranksByStrongestChunkAndSkipsMissing() {
        // 当前笔记：两个分片，方向都接近 (1,0,0,0)
        List<VectorIndex.Chunk> chunks = List.of(
                chunk(1, 1, new float[] {0.9f, 0.1f, 0f, 0f}),
                chunk(2, 1, new float[] {0.8f, 0.2f, 0f, 0f}),
                // 相关笔记：分片方向接近当前笔记 → 高分
                chunk(3, 2, new float[] {0.95f, 0.05f, 0f, 0f}),
                // 无关笔记：正交方向 → 0 分，低于阈值
                chunk(4, 3, new float[] {0f, 0f, 1f, 0f}),
                // 已删除：索引快照还没失效，repo 里查不到
                chunk(5, 4, new float[] {0.99f, 0.01f, 0f, 0f}));
        when(index.chunks()).thenReturn(chunks);
        when(noteRepository.findById(2L)).thenReturn(Optional.of(note(2, "相关笔记")));
        // 4 号（已删）返回空
        lenient().when(noteRepository.findById(4L)).thenReturn(Optional.empty());
        // 3 号（正交）分数为 0，不会走到 repository

        List<RelatedNoteDto> result = service.related(1, null);

        assertThat(result).hasSize(1);
        assertThat(result).extracting(RelatedNoteDto::id).doesNotContain(3L, 4L);
        assertThat(result.get(0).id()).isEqualTo(2L);
        assertThat(result.get(0).score()).isGreaterThan(RelatedNoteService.MIN_SCORE);
        assertThat(result.get(0).slug()).isEqualTo("slug-2");
    }

    @Test
    @DisplayName("当前笔记没有分片（未建索引）→ 空列表，不调 repository")
    void returnsEmptyWhenNotIndexed() {
        when(index.chunks()).thenReturn(List.of(chunk(1, 9, new float[] {1f, 0f, 0f, 0f})));

        assertThat(service.related(1, 5)).isEmpty();
    }

    @Test
    @DisplayName("分片维度不一致（换过 embedding 模型）→ 放弃推荐而不是算出垃圾分")
    void givesUpOnDimensionMismatch() {
        List<VectorIndex.Chunk> chunks = List.of(
                chunk(1, 1, new float[] {1f, 0f, 0f, 0f}),
                chunk(2, 1, new float[] {1f, 0f, 0f}),
                chunk(3, 2, new float[] {1f, 0f, 0f, 0f}));
        when(index.chunks()).thenReturn(chunks);

        assertThat(service.related(1, 5)).isEmpty();
    }

    @Test
    @DisplayName("limit 被夹取：null→5，超过 10 取 10")
    void clampsLimit() {
        // 12 篇都同方向，全部超过阈值
        var builder = new java.util.ArrayList<VectorIndex.Chunk>();
        builder.add(chunk(0, 1, new float[] {1f, 0f, 0f, 0f}));
        for (long i = 2; i <= 13; i++) {
            builder.add(chunk(i, i, new float[] {1f, 0f, 0f, 0f}));
            Note note = note(i, "笔记" + i);
            lenient().when(noteRepository.findById(i)).thenReturn(Optional.of(note));
        }
        when(index.chunks()).thenReturn(builder);

        assertThat(service.related(1, null)).hasSize(5);
        assertThat(service.related(1, 99)).hasSize(RelatedNoteService.MAX_LIMIT);
        assertThat(service.related(1, 0)).hasSize(1);
    }

    @Test
    @DisplayName("noteId 不存在时 controller 先 requireExists，这里只保证不误推荐")
    void unknownNoteIsNotRecommendedAsItself() {
        when(index.chunks()).thenReturn(List.of(chunk(1, 42, new float[] {1f, 0f, 0f, 0f})));

        assertThat(service.related(7, 5)).isEmpty();
    }

    @Test
    @DisplayName("requireExists：不存在的笔记抛 404 语义异常")
    void requireExistsThrows() {
        when(noteRepository.findById(anyLong())).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireExists(99))
                .isInstanceOf(com.notesblog.common.NotFoundException.class);
    }
}
