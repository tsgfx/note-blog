package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Comment;
import com.notesblog.domain.CommentStatus;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.AdminCommentDto;
import com.notesblog.dto.CommentDto;
import com.notesblog.dto.CreateCommentRequest;
import com.notesblog.repository.CommentRepository;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论的读写。
 *
 * <p><b>默认可见</b>：新建即 {@code VISIBLE}，没有审核队列（理由见 {@link CommentStatus}）。
 * 管理员的权限是「事后隐藏/删除」，不是「事先逐条放行」。
 *
 * <p><b>只能评论已发布的文章</b>：评论挂 note_id 外键，草稿的 id 也是有效 id，
 * 不校验的话拿一个从别处看到的 id 就能给未发布内容留言，等于确认了草稿存在。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final CommentRepository commentRepository;
    private final NoteRepository noteRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /** 公开列表：只返回 VISIBLE，按时间正序。 */
    @Transactional(readOnly = true)
    public Page<CommentDto> publicList(String slug, int page, int size) {
        Note note = publishedNote(slug);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        return commentRepository
                .findByNoteIdAndStatusOrderByCreatedAtAsc(note.getId(), CommentStatus.VISIBLE, pageable)
                .map(CommentDto::from);
    }

    /**
     * 游客发评论。
     *
     * <p>蜜罐判断由这里自己做（读 {@link CreateCommentRequest#honeypotFilled()}），
     * 不接受调用方传布尔值：两处判断迟早会不同步，而这里只看「填没填」一个事实。
     *
     * @return 落库后的公开视图；蜜罐命中时返回 null（调用方照样回 200）
     */
    @Transactional
    public CommentDto create(String slug, CreateCommentRequest request, String clientIp) {
        Note note = publishedNote(slug);
        if (request.honeypotFilled()) {
            log.info("comment_honeypot_hit slug={} ip={}", slug, clientIp);
            return null;
        }
        Comment comment = new Comment();
        comment.setNoteId(note.getId());
        comment.setContent(request.content().trim());
        comment.setStatus(CommentStatus.VISIBLE);
        comment.setClientIp(clientIp);
        commentRepository.save(comment);
        log.info("comment_created id={} note={} ip={}", comment.getId(), note.getId(), clientIp);
        notificationService.notifyNewComment(note.getTitle(), note.getSlug(), comment.getContent(), clientIp);
        return CommentDto.from(comment);
    }

    /* ───────── 管理端 ───────── */

    @Transactional(readOnly = true)
    public Page<AdminCommentDto> adminList(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Comment> comments = commentRepository.findAllByOrderByCreatedAtDesc(pageable);

        // 一次取齐涉及的笔记标题，避免每行一次查询
        List<Long> noteIds = comments.getContent().stream().map(Comment::getNoteId).distinct().toList();
        Map<Long, Note> notes = noteIds.isEmpty()
                ? Map.of()
                : noteRepository.findAllById(noteIds).stream()
                        .collect(Collectors.toMap(Note::getId, Function.identity()));

        return comments.map(c -> {
            Note note = notes.get(c.getNoteId());
            return AdminCommentDto.from(c, note == null ? null : note.getTitle(),
                    note == null ? null : note.getSlug());
        });
    }

    /** 改可见性。隐藏是软隐藏，内容留着，随时能放回来。 */
    @Transactional
    public AdminCommentDto setStatus(Long id, CommentStatus status) {
        Comment comment = commentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("评论不存在：" + id));
        comment.setStatus(status);
        commentRepository.save(comment);
        auditService.record("COMMENT_" + status.name(), String.valueOf(id),
                truncate(comment.getContent(), 120));
        Note note = noteRepository.findById(comment.getNoteId()).orElse(null);
        return AdminCommentDto.from(comment, note == null ? null : note.getTitle(),
                note == null ? null : note.getSlug());
    }

    /** 真删除。管理端才有的操作，游客只能发。 */
    @Transactional
    public void delete(Long id) {
        Comment comment = commentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("评论不存在：" + id));
        commentRepository.delete(comment);
        auditService.record("COMMENT_DELETE", String.valueOf(id), truncate(comment.getContent(), 120));
    }

    /* ───────── 内部 ───────── */

    /** 取已发布笔记；草稿或不存在的 slug 一律 404，不暴露「存在但没发布」。 */
    private Note publishedNote(String slug) {
        return noteRepository.findBySlug(slug)
                .filter(note -> note.getStatus() == NoteStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("公开文章不存在：" + slug));
    }

    private static int clampSize(int size) {
        if (size < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
