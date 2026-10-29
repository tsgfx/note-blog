package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.repository.NoteChunkRepository;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * 补建缺失索引：只给「有正文但没分片」的笔记排队。
 *
 * <p>这类笔记是嵌入服务短暂不可用、或从备份恢复（备份不含分片数据）后留下的，
 * 列表里看不出异常，语义检索却永远搜不到，属于静默失效。
 *
 * <p>「正文为空」与「已有分片」的筛选现在由 JPQL 一次完成，所以这里只验证
 * 排队的边界：不超过队列容量、不因为偶尔的满队列让整个请求失败。
 */
@ExtendWith(MockitoExtension.class)
class NoteReindexTest {

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

    @Test
    @DisplayName("缺分片的笔记逐个排队，并记一条审计")
    void queuesNotesWithoutChunks() {
        when(noteRepository.findMissingChunkNoteIds(any(Pageable.class))).thenReturn(List.of(1L));

        int queued = service.reindexMissing();

        assertThat(queued).isEqualTo(1);
        verify(noteIndexService).reindex(1L);
        verify(auditService).record("NOTE_REINDEX", "all", "补建缺失索引 1 篇");
    }

    @Test
    @DisplayName("没有缺分片的笔记：不排队也不写审计")
    void skipsWhenNothingMissing() {
        when(noteRepository.findMissingChunkNoteIds(any(Pageable.class))).thenReturn(List.of());

        assertThat(service.reindexMissing()).isZero();

        verify(noteIndexService, never()).reindex(anyLong());
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("线程池队列满：停止排队并返回已排数量，不让整个接口 500")
    void stopsWhenQueueFull() {
        // 从备份恢复后全库都缺分片，一次性排 680 篇必然撞上只有 20 个槽位的队列
        when(noteRepository.findMissingChunkNoteIds(any(Pageable.class))).thenReturn(List.of(4L, 5L));
        // 用 doAnswer 而不是 doThrow(5L)：参数不匹配的桩会触发 Strict stubbing 报错
        org.mockito.Mockito.doAnswer(inv -> {
            if (Long.valueOf(5L).equals(inv.getArgument(0))) {
                throw new java.util.concurrent.RejectedExecutionException("queue full");
            }
            return null;
        }).when(noteIndexService).reindex(anyLong());

        int queued = service.reindexMissing();

        assertThat(queued).isEqualTo(1);
        verify(noteIndexService).reindex(4L);
    }

    @Test
    @DisplayName("队列满时审计里标出「余下的需再次触发」，避免以为一次就补完了")
    void auditMentionsRemainingWhenQueueFull() {
        when(noteRepository.findMissingChunkNoteIds(any(Pageable.class))).thenReturn(List.of(6L, 7L));
        org.mockito.Mockito.doAnswer(inv -> {
            if (Long.valueOf(7L).equals(inv.getArgument(0))) {
                throw new java.util.concurrent.RejectedExecutionException("queue full");
            }
            return null;
        }).when(noteIndexService).reindex(anyLong());

        service.reindexMissing();

        verify(auditService).record("NOTE_REINDEX", "all", "补建缺失索引 1 篇（队列已满，余下的需再次触发）");
    }
}
