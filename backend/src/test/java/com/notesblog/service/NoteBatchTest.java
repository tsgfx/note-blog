package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.BatchResultDto;
import com.notesblog.repository.NoteChunkRepository;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 批量发布的跳过规则与计数口径。纯单元测试。 */
@ExtendWith(MockitoExtension.class)
class NoteBatchTest {

    @Mock
    private NoteRepository noteRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private NoteChunkRepository chunkRepository;
    @Mock
    private NoteIndexService noteIndexService;
    @Mock
    private AuditService auditService;
    @Mock
    private VectorIndex vectorIndex;

    private NoteService service;

    @BeforeEach
    void setUp() {
        service = new NoteService(noteRepository, tagRepository, chunkRepository,
                noteIndexService, auditService, vectorIndex);
    }

    private Note note(long id, String title, String content, NoteStatus status) {
        Note note = new Note();
        note.setId(id);
        note.setTitle(title);
        note.setContentMd(content);
        note.setStatus(status);
        return note;
    }

    @Test
    @DisplayName("正文为空：跳过并给出原因")
    void blankContentIsSkipped() {
        Note blank = note(1L, "空笔记", "   ", NoteStatus.DRAFT);
        when(noteRepository.findAllById(anyList())).thenReturn(List.of(blank));

        BatchResultDto result = service.publishBatch(List.of(1L));

        assertThat(result.succeeded()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.skippedItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("正文为空"));
        assertThat(blank.getStatus()).isEqualTo(NoteStatus.DRAFT);
        verify(noteRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("不存在的 id：记为跳过，不抛异常")
    void unknownIdsAreReported() {
        when(noteRepository.findAllById(anyList())).thenReturn(List.of());

        BatchResultDto result = service.publishBatch(List.of(99L));

        assertThat(result.skippedItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(99L);
                    assertThat(item.reason()).isEqualTo("笔记不存在");
                });
    }

    @Test
    @DisplayName("已发布的不重复发布，publishedAt 不被刷新")
    void alreadyPublishedIsSkippedWithoutTouchingTime() {
        LocalDateTime old = LocalDateTime.of(2020, 1, 1, 0, 0);
        Note published = note(2L, "旧文", "正文", NoteStatus.PUBLISHED);
        published.setPublishedAt(old);
        when(noteRepository.findAllById(anyList())).thenReturn(List.of(published));

        BatchResultDto result = service.publishBatch(List.of(2L));

        assertThat(result.succeeded()).isZero();
        assertThat(published.getPublishedAt()).isEqualTo(old);
        verify(noteRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("下线草稿：跳过")
    void unpublishDraftIsSkipped() {
        Note draft = note(3L, "草稿", "正文", NoteStatus.DRAFT);
        when(noteRepository.findAllById(anyList())).thenReturn(List.of(draft));

        BatchResultDto result = service.unpublishBatch(List.of(3L));

        assertThat(result.succeeded()).isZero();
        assertThat(result.skippedItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("已经是草稿"));
    }

    @Test
    @DisplayName("成功 2 篇 + 跳过 1 篇：审计只写一条汇总")
    void auditWritesSingleSummary() {
        Note ok1 = note(4L, "一", "正文", NoteStatus.DRAFT);
        Note ok2 = note(5L, "二", "正文", NoteStatus.DRAFT);
        Note blank = note(6L, "三", "", NoteStatus.DRAFT);
        when(noteRepository.findAllById(anyList())).thenReturn(List.of(ok1, ok2, blank));

        BatchResultDto result = service.publishBatch(List.of(4L, 5L, 6L));

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(result.succeeded() + result.skipped());
        verify(auditService).record(eq("NOTE_BATCH_PUBLISH"), isNull(),
                eq("成功 2 篇，跳过 1 篇"));
        verify(noteRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("重复 id 只处理一次")
    void duplicateIdsAreCollapsed() {
        Note draft = note(7L, "七", "正文", NoteStatus.DRAFT);
        when(noteRepository.findAllById(anyList())).thenReturn(List.of(draft));

        BatchResultDto result = service.publishBatch(List.of(7L, 7L, 7L));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有变化时不写审计（不留噪音）")
    void nothingChangedWritesNoAudit() {
        when(noteRepository.findAllById(anyList())).thenReturn(List.of());

        BatchResultDto result = service.publishBatch(List.of());

        assertThat(result.total()).isZero();
        assertThat(result.succeeded()).isZero();
        assertThat(result.skipped()).isZero();
        verifyNoInteractions(auditService);
        verify(noteRepository, never()).saveAll(anyList());
        verify(noteRepository, never()).save(any());
    }
}
