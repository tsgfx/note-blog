package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Comment;
import com.notesblog.domain.CommentStatus;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.CommentDto;
import com.notesblog.dto.CreateCommentRequest;
import com.notesblog.repository.CommentRepository;
import com.notesblog.repository.NoteRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 评论的写入规则。
 *
 * <p>三条要守住的性质：
 * <ol>
 *   <li>默认可见——用户明确要求「没设置隐藏或删除就默认可见」，没有审核队列</li>
 *   <li>只能评论已发布文章——否则拿一个 id 就能给草稿留言，等于确认草稿存在</li>
 *   <li>蜜罐命中静默丢弃——报错等于告诉机器人「这个字段是陷阱」</li>
 * </ol>
 */
class CommentServiceTest {

    private CommentRepository commentRepository;
    private NoteRepository noteRepository;
    private AuditService auditService;
    private CommentService service;

    @BeforeEach
    void setUp() {
        commentRepository = mock(CommentRepository.class);
        noteRepository = mock(NoteRepository.class);
        auditService = mock(AuditService.class);
        service = new CommentService(commentRepository, noteRepository, auditService);
    }

    private void aNote(String slug, NoteStatus status) {
        Note note = new Note();
        note.setId(42L);
        note.setSlug(slug);
        note.setStatus(status);
        when(noteRepository.findBySlug(slug)).thenReturn(Optional.of(note));
    }

    private Comment capturedComment() {
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("新评论默认可见：没有审核队列")
    void newCommentIsVisibleByDefault() {
        aNote("hello", NoteStatus.PUBLISHED);

        CommentDto dto = service.create("hello",
                new CreateCommentRequest("写得很好", null), "1.2.3.4");

        Comment saved = capturedComment();
        assertThat(saved.getStatus()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(dto.author()).isEqualTo(CommentDto.GUEST_AUTHOR);
        assertThat(dto.content()).isEqualTo("写得很好");
    }

    @Test
    @DisplayName("公开视图不含客户端 IP：评论是公开的，IP 不能带出去")
    void publicViewHasNoIp() {
        aNote("hello", NoteStatus.PUBLISHED);

        CommentDto dto = service.create("hello",
                new CreateCommentRequest("内容", null), "203.0.113.9");

        // 公开 DTO 里根本没有 ip 字段，这一条靠类型保证；
        // 这里额外确认 clientIp 确实存到了库里（管理端追溯要有）
        assertThat(capturedComment().getClientIp()).isEqualTo("203.0.113.9");
        assertThat(dto.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("clientIp");
    }

    @Test
    @DisplayName("给草稿评论一律 404：不暴露「存在但没发布」")
    void cannotCommentOnDraft() {
        aNote("draft-post", NoteStatus.DRAFT);

        assertThatThrownBy(() -> service.create("draft-post",
                new CreateCommentRequest("内容", null), "1.2.3.4"))
                .isInstanceOf(NotFoundException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("蜜罐命中：返回 null 且不落库")
    void honeypotDoesNotPersist() {
        aNote("hello", NoteStatus.PUBLISHED);

        CommentDto dto = service.create("hello",
                new CreateCommentRequest("买买买 http://spam.example", true), "1.2.3.4");

        assertThat(dto).isNull();
        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("内容首尾空白被裁掉：避免一条只有空格的评论占位")
    void contentIsTrimmed() {
        aNote("hello", NoteStatus.PUBLISHED);

        service.create("hello", new CreateCommentRequest("  有内容  ", null), "1.2.3.4");

        assertThat(capturedComment().getContent()).isEqualTo("有内容");
    }

    @Test
    @DisplayName("隐藏是软隐藏：只改状态，不删数据")
    void hideKeepsContent() {
        Comment existing = new Comment();
        existing.setId(7L);
        existing.setNoteId(42L);
        existing.setContent("会被隐藏的内容");
        existing.setStatus(CommentStatus.VISIBLE);
        when(commentRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(noteRepository.findById(42L)).thenReturn(Optional.empty());

        service.setStatus(7L, CommentStatus.HIDDEN);

        assertThat(existing.getStatus()).isEqualTo(CommentStatus.HIDDEN);
        assertThat(existing.getContent()).isEqualTo("会被隐藏的内容");
        verify(commentRepository, never()).delete(any());
        // 管理操作要留痕
        verify(auditService).record(any(), any(), any());
    }
}
