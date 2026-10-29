package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.dto.GenerateMetaBatchRequest;
import com.notesblog.dto.MetaBatchReportDto;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * 批量补元数据：规则要卡准，因为每一次调用都在花模型额度。
 *
 * <p>三件必须成立的事：已有内容的不重写、单篇失败不影响其余、
 * 到量就停（不因为 hasMore 判断失误把整库跑一遍）。
 */
@ExtendWith(MockitoExtension.class)
class NoteMetaBatchServiceTest {

    @Mock
    private NoteRepository noteRepository;
    @Mock
    private NoteMetaService noteMetaService;
    @Mock
    private AuditService auditService;

    private NoteMetaBatchService service;

    @BeforeEach
    void setUp() {
        service = new NoteMetaBatchService(noteRepository, noteMetaService, auditService);
    }

    /** 显式声明 List<Object[]>：双参 List.of 会被推断成 List<Object>，Mockito 就不认了 */
    private static List<Object[]> states(Object[]... rows) {
        return List.of(rows);
    }

    /** [id, title, 摘要是否空白, 标签个数] */
    private static Object[] state(long id, String title, boolean missingSummary, int tagCount) {
        return new Object[] {id, title, missingSummary, tagCount};
    }

    @Test
    @DisplayName("不传 ids：自动挑缺失的笔记，逐篇生成")
    void generatesMissingAutomatically() {
        when(noteRepository.findMissingMetaIds(any(Pageable.class))).thenReturn(List.of(10L, 11L));
        when(noteRepository.findMetaState(List.of(10L, 11L))).thenReturn(states(
                state(10L, "无摘要", true, 3),
                state(11L, "无标签", false, 0)));
        when(noteRepository.countMissingMeta()).thenReturn(0L);

        MetaBatchReportDto report = service.generateBatch(new GenerateMetaBatchRequest(null, false, 5));

        assertThat(report.requested()).isEqualTo(2);
        assertThat(report.generated()).isEqualTo(2);
        assertThat(report.skipped()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.hasMore()).isFalse();
        verify(noteMetaService).generate(10L, false);
        verify(noteMetaService).generate(11L, false);
        verify(auditService).record("NOTE_META_BATCH", "all", "批量补元数据 2 篇");
    }

    @Test
    @DisplayName("摘要和标签都齐：跳过，不花额度")
    void skipsNotesWithCompleteMeta() {
        // 传了 ids 就走「指定这批」分支，不会去查 findMissingMetaIds
        when(noteRepository.findMetaState(List.of(20L)))
                .thenReturn(states(state(20L, "已完整", false, 4)));
        when(noteRepository.countMissingMeta()).thenReturn(0L);

        MetaBatchReportDto report = service.generateBatch(new GenerateMetaBatchRequest(List.of(20L), false, 5));

        assertThat(report.generated()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        verify(noteMetaService, never()).generate(anyLong(), anyBoolean());
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("force 时已有内容也重写")
    void rewritesWhenForced() {
        when(noteRepository.findMissingMetaIds(any(Pageable.class))).thenReturn(List.of(30L));
        when(noteRepository.findMetaState(List.of(30L)))
                .thenReturn(states(state(30L, "已完整", false, 4)));
        when(noteRepository.countMissingMeta()).thenReturn(0L);

        MetaBatchReportDto report = service.generateBatch(new GenerateMetaBatchRequest(null, true, 5));

        assertThat(report.generated()).isEqualTo(1);
        verify(noteMetaService).generate(30L, true);
    }

    @Test
    @DisplayName("单篇失败不中断其余，失败明细进 failures 供重试")
    void keepsGoingAfterFailure() {
        when(noteRepository.findMissingMetaIds(any(Pageable.class))).thenReturn(List.of(40L, 41L));
        when(noteRepository.findMetaState(List.of(40L, 41L))).thenReturn(states(
                state(40L, "会失败", true, 0),
                state(41L, "会成功", true, 0)));
        when(noteMetaService.generate(40L, false))
                .thenThrow(new LlmException(LlmException.Kind.TRANSIENT, "模型返回的不是 JSON"));
        when(noteRepository.countMissingMeta()).thenReturn(1L);

        MetaBatchReportDto report = service.generateBatch(new GenerateMetaBatchRequest(null, false, 5));

        assertThat(report.generated()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0).id()).isEqualTo(40L);
        assertThat(report.failures().get(0).reason()).contains("JSON");
        verify(noteMetaService).generate(41L, false);
    }

    @Test
    @DisplayName("超过单批上限：只处理 limit 篇，hasMore 为真")
    void capsBatchAndReportsMore() {
        // 仓库按 limit+1 取，返回 6 条说明还有剩余
        when(noteRepository.findMissingMetaIds(any(Pageable.class)))
                .thenReturn(List.of(50L, 51L, 52L, 53L, 54L, 55L));
        when(noteRepository.findMetaState(List.of(50L, 51L, 52L, 53L, 54L)))
                .thenReturn(states(
                        state(50L, "a", true, 0), state(51L, "b", true, 0), state(52L, "c", true, 0),
                        state(53L, "d", true, 0), state(54L, "e", true, 0)));
        when(noteRepository.countMissingMeta()).thenReturn(1L);

        MetaBatchReportDto report = service.generateBatch(new GenerateMetaBatchRequest(null, false, 5));

        assertThat(report.requested()).isEqualTo(5);
        assertThat(report.hasMore()).isTrue();
        assertThat(report.remaining()).isEqualTo(1L);
        verify(noteMetaService, never()).generate(eq(55L), anyBoolean());
    }

    @Test
    @DisplayName("候选为空：直接返回零，不写审计")
    void handlesEmptyCandidate() {
        when(noteRepository.findMissingMetaIds(any(Pageable.class))).thenReturn(List.of());
        when(noteRepository.countMissingMeta()).thenReturn(0L);

        MetaBatchReportDto report = service.generateBatch(new GenerateMetaBatchRequest(null, false, 5));

        assertThat(report.requested()).isZero();
        assertThat(report.hasMore()).isFalse();
        verify(noteMetaService, never()).generate(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("取最内层原因：外层包装只说「调用失败」，没信息量")
    void unwrapsRootCause() {
        RuntimeException wrapped = new IllegalStateException("调用失败",
                new java.io.IOException("Connection reset"));

        assertThat(NoteMetaBatchService.rootMessage(wrapped)).isEqualTo("Connection reset");
    }
}
