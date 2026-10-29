package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.repository.NoteChunkRepository;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 删除笔记时的连带清理。
 *
 * <p>{@code note_chunks} 是指向笔记的独立行，没有外键级联；不显式删就会留下
 * 查不到主人的向量块——检索会命中已删笔记的片段，表也会一直变大。
 */
@ExtendWith(MockitoExtension.class)
class NoteDeleteTest {

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
    @DisplayName("删除笔记时一并清掉它的切块，不留孤儿向量")
    void deleteRemovesChunks() {
        Note note = new Note();
        note.setId(42L);
        note.setTitle("空笔记");
        note.setSlug("empty-note");
        when(noteRepository.findById(42L)).thenReturn(Optional.of(note));

        service.delete(42L);

        verify(chunkRepository).deleteByNoteId(42L);
        verify(noteRepository).delete(note);
        // 向量索引必须失效，否则下一次检索还会用旧缓存
        verify(vectorIndex).invalidate();
    }

    @Test
    @DisplayName("删除不存在的笔记时报 404，且不动切块")
    void deleteMissingNoteDoesNothing() {
        when(noteRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(com.notesblog.common.NotFoundException.class);

        verifyNoInteractions(chunkRepository);
        verify(noteRepository, never()).delete(any(Note.class));
    }
}
