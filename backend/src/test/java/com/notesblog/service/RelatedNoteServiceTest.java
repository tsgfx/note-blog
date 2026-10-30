package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.domain.Tag;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.repository.NoteRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static Tag tag(String name) {
        Tag t = new Tag();
        t.setName(name);
        return t;
    }

    /**
     * 模拟批量取笔记：服务现在用 findAllById 一次取回候选，
     * 缺 id（已删除）与真实仓库一样被略过。
     */
    private void stubBulkLoad(Note... notes) {
        Map<Long, Note> byId = new HashMap<>();
        for (Note n : notes) {
            byId.put(n.getId(), n);
        }
        lenient().when(noteRepository.findAllById(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        });
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
        // 4 号（已删）批量取时找不到；3 号（正交）分数为 0，根本不在候选里
        stubBulkLoad(note(2, "相关笔记"));

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
        var loaded = new java.util.ArrayList<Note>();
        for (long i = 2; i <= 13; i++) {
            builder.add(chunk(i, i, new float[] {1f, 0f, 0f, 0f}));
            loaded.add(note(i, "笔记" + i));
        }
        when(index.chunks()).thenReturn(builder);
        stubBulkLoad(loaded.toArray(new Note[0]));

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

    @Test
    @DisplayName("多路打分：同专栏与标签重叠的文章得分获得提升并标注推荐原因")
    void hybridScoringBoostsSameSeriesAndTagOverlap() {
        Tag t1 = tag("Spring");
        Tag t2 = tag("专栏:深入微服务");

        Note current = note(1, "微服务架构导论");
        current.setTags(Set.of(t1, t2));
        current.setSourcePath("后端/架构/intro.md");
        when(noteRepository.findById(1L)).thenReturn(Optional.of(current));

        Note candidateA = note(2, "服务注册与发现");
        candidateA.setTags(Set.of(t1, t2));
        candidateA.setSourcePath("后端/架构/eureka.md");

        Note candidateB = note(3, "另一篇普通文章");
        candidateB.setTags(Set.of(tag("Python")));
        candidateB.setSourcePath("大数据/python.md");

        List<VectorIndex.Chunk> chunks = List.of(
                chunk(1, 1, new float[] {1f, 0f, 0f, 0f}),
                chunk(2, 2, new float[] {0.9f, 0.1f, 0f, 0f}),
                chunk(3, 3, new float[] {0.95f, 0.05f, 0f, 0f}));
        when(index.chunks()).thenReturn(chunks);
        stubBulkLoad(candidateA, candidateB);

        List<RelatedNoteDto> results = service.related(1, 5);
        assertThat(results).isNotEmpty();
        // candidateA 虽然向量分略低于 candidateB，但由于专栏和标签相同，综合多路分跃居第一
        assertThat(results.get(0).id()).isEqualTo(2L);
        assertThat(results.get(0).reason()).contains("深入微服务");
    }

    @Test
    @DisplayName("类目打散重排：避免同一目录下的文章扎堆霸榜，保持推荐内容丰富度")
    void diversityRerankingPreventsCategoryClustering() {
        Note current = note(1, "当前文章");
        when(noteRepository.findById(1L)).thenReturn(Optional.of(current));

        // 3 篇来自同一类目 A，1 篇来自类目 B
        Note n2 = note(2, "类目A-1");
        n2.setSourcePath("backend/java/1.md");
        Note n3 = note(3, "类目A-2");
        n3.setSourcePath("backend/java/2.md");
        Note n4 = note(4, "类目A-3");
        n4.setSourcePath("backend/java/3.md");
        Note n5 = note(5, "类目B-1");
        n5.setSourcePath("frontend/vue/1.md");

        List<VectorIndex.Chunk> chunks = List.of(
                chunk(1, 1, new float[] {1f, 0f, 0f, 0f}),
                chunk(2, 2, new float[] {0.99f, 0.01f, 0f, 0f}),
                chunk(3, 3, new float[] {0.98f, 0.02f, 0f, 0f}),
                chunk(4, 4, new float[] {0.97f, 0.03f, 0f, 0f}),
                chunk(5, 5, new float[] {0.90f, 0.10f, 0f, 0f}));
        when(index.chunks()).thenReturn(chunks);
        stubBulkLoad(n2, n3, n4, n5);

        // 请求 3 篇：单类目上限为 max(2, 3/2) = 2
        // 类目 A 选 2 篇，第 3 篇必须轮到不同类目的 n5
        List<RelatedNoteDto> results = service.related(1, 3);
        assertThat(results).hasSize(3);
        assertThat(results).extracting(RelatedNoteDto::id).containsExactly(2L, 3L, 5L);
    }
}
