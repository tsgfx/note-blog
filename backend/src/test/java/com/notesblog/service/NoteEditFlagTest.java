package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.BatchResultDto;
import com.notesblog.dto.UpdateNoteRequest;
import com.notesblog.repository.NoteChunkRepository;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 「已发布文章被改过、还没重新发布」这个标记的规则。
 *
 * <p>两头的边界都要卡住：改了就置位（否则线上内容和本地不一致却没人知道），
 * 没改就别置位（否则每保存一次都亮提示，很快就没人看了）。
 */
@ExtendWith(MockitoExtension.class)
class NoteEditFlagTest {

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

    private Note published(String content) {
        Note note = new Note();
        note.setId(1L);
        note.setTitle("已发布的文章");
        note.setSlug("pub-1");
        note.setSummary("摘要");
        note.setContentMd(content);
        note.setStatus(NoteStatus.PUBLISHED);
        return note;
    }

    private static UpdateNoteRequest content(String md) {
        return new UpdateNoteRequest(null, null, md, null);
    }

    @Test
    @DisplayName("已发布笔记改了正文：置位，提示线上仍是旧内容")
    void marksEditOnPublishedNote() {
        Note note = published("旧正文");
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.update(1L, content("新正文"));

        assertThat(dto.hasUnpublishedEdits()).isTrue();
        assertThat(note.isHasUnpublishedEdits()).isTrue();
    }

    @Test
    @DisplayName("内容没变就保存：不置位，避免每次打开编辑器都亮提示")
    void doesNotMarkWhenNothingChanged() {
        Note note = published("同一份正文");
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.update(1L, content("同一份正文"));

        assertThat(dto.hasUnpublishedEdits()).isFalse();
    }

    @Test
    @DisplayName("草稿随便改：不置位，它本来就没上线")
    void ignoresDrafts() {
        Note note = published("草稿正文");
        note.setStatus(NoteStatus.DRAFT);
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.update(1L, content("改过的草稿正文"));

        assertThat(dto.hasUnpublishedEdits()).isFalse();
    }

    @Test
    @DisplayName("重新发布：标记清零")
    void clearsOnPublish() {
        Note note = published("正文");
        note.setHasUnpublishedEdits(true);
        note.setStatus(NoteStatus.DRAFT);
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.publish(1L);

        assertThat(dto.hasUnpublishedEdits()).isFalse();
        assertThat(note.isHasUnpublishedEdits()).isFalse();
    }

    @Test
    @DisplayName("批量发布：同样清零")
    void clearsOnBatchPublish() {
        Note note = published("正文");
        note.setHasUnpublishedEdits(true);
        note.setStatus(NoteStatus.DRAFT);
        when(noteRepository.findAllById(any())).thenReturn(List.of(note));
        when(noteRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        BatchResultDto result = service.publishBatch(List.of(1L));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(note.isHasUnpublishedEdits()).isFalse();
    }

    @Test
    @DisplayName("下线：标记也清零，下线后没有「线上内容」可比")
    void clearsOnUnpublish() {
        Note note = published("正文");
        note.setHasUnpublishedEdits(true);
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.unpublish(1L);

        assertThat(dto.hasUnpublishedEdits()).isFalse();
    }

    @Test
    @DisplayName("改标题也算内容改动")
    void titleChangeAlsoMarks() {
        Note note = published("正文");
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(1L, new UpdateNoteRequest("新标题", null, null, null));

        assertThat(note.isHasUnpublishedEdits()).isTrue();
    }

    @Test
    @DisplayName("只有标签变了也算：博客列表按标签筛选，线上会不同步")
    void tagChangeAlsoMarks() {
        Note note = published("正文");
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tagRepository.findByName("java")).thenReturn(Optional.empty());
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(1L, new UpdateNoteRequest(null, null, null, List.of("java")));

        assertThat(note.isHasUnpublishedEdits()).isTrue();
        verifyNoIndexRebuild();
    }

    /** 只改标签不该触发重建索引：那要调 embedding，白花钱 */
    private void verifyNoIndexRebuild() {
        org.mockito.Mockito.verify(noteIndexService, org.mockito.Mockito.never()).reindex(anyLong());
        org.mockito.Mockito.verify(chunkRepository, org.mockito.Mockito.never()).deleteByNoteId(anyLong());
    }
}
