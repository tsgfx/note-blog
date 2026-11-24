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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final int MAX_CONTENT = 2000;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final CommentRepository commentRepository;
    private final NoteRepository noteRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /**
     * 公开列表：顶层评论分页，回复挂在父评论下面一起返回。
     *
     * <p>只取 VISIBLE；回复不单独占分页位——否则一条被回三次的评论会把整页挤满。
     */
    @Transactional(readOnly = true)
    public Page<CommentDto> publicList(String slug, int page, int size) {
        Note note = publishedNote(slug);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Comment> roots = commentRepository
                .findByNoteIdAndStatusAndParentIdIsNullOrderByCreatedAtAsc(
                        note.getId(), CommentStatus.VISIBLE, pageable);

        List<Long> rootIds = roots.getContent().stream().map(Comment::getId).toList();
        Map<Long, List<CommentDto>> replies = new LinkedHashMap<>();
        if (!rootIds.isEmpty()) {
            commentRepository.findByParentIdInAndStatusOrderByCreatedAtAsc(rootIds, CommentStatus.VISIBLE)
                    .forEach(reply -> replies
                            .computeIfAbsent(reply.getParentId(), key -> new ArrayList<>())
                            .add(CommentDto.from(reply)));
        }
        return roots.map(root -> withReplies(CommentDto.from(root),
                replies.getOrDefault(root.getId(), List.of())));
    }

    private static CommentDto withReplies(CommentDto base, List<CommentDto> replies) {
        return new CommentDto(base.id(), base.author(), base.content(), base.createdAt(),
                base.parentId(), base.authorReply(), List.copyOf(replies));
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
        comment.setParentId(visibleRootId(note.getId(), request.parentId()));
        comment.setContent(request.content().trim());
        comment.setStatus(CommentStatus.VISIBLE);
        comment.setClientIp(clientIp);
        commentRepository.save(comment);
        log.info("comment_created id={} note={} ip={}", comment.getId(), note.getId(), clientIp);
        notificationService.notifyNewComment(note.getTitle(), note.getSlug(), comment.getContent(), clientIp);
        return CommentDto.from(comment);
    }

    /**
     * 博主回复某条评论（管理端接口）。
     *
     * <p>回复自身也是 comments 表里的一行，{@code author_reply = true}：
     * 公开页据此显示「博主」并加底色，不必另建一张表。管理员身份来自登录态，
     * 不读请求体里的任何「我是谁」字段。
     */
    @Transactional
    public AdminCommentDto reply(Long parentId, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("回复内容不能为空");
        }
        if (text.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("回复内容最多 " + MAX_CONTENT + " 字");
        }
        Comment parent = commentRepository.findById(parentId)
                .orElseThrow(() -> new NotFoundException("评论不存在：" + parentId));
        if (parent.getParentId() != null) {
            throw new IllegalArgumentException("只能回复顶层评论，不支持对回复再回复");
        }
        // 已隐藏的评论不能再拿到回复：否则回复会出现在公开页上，等于绕过了隐藏
        if (parent.getStatus() != CommentStatus.VISIBLE) {
            throw new NotFoundException("评论不存在：" + parentId);
        }
        Comment reply = new Comment();
        reply.setNoteId(parent.getNoteId());
        reply.setParentId(parent.getId());
        reply.setAuthorReply(true);
        reply.setContent(text);
        reply.setStatus(CommentStatus.VISIBLE);
        commentRepository.save(reply);
        auditService.record("COMMENT_REPLY", String.valueOf(parent.getId()), truncate(text, 120));
        log.info("comment_replied parent={} reply={}", parent.getId(), reply.getId());
        Note note = noteRepository.findById(parent.getNoteId()).orElse(null);
        return AdminCommentDto.from(reply, note == null ? null : note.getTitle(),
                note == null ? null : note.getSlug());
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
    /**
     * 游客回复：只接受「同属一篇已发布文章、且仍可见的顶层评论」的 id。
     *
     * <p>不校验的话，拿别人的 id 就能把回复挂到未发布文章或已隐藏的评论下面。
     */
    private Long visibleRootId(Long noteId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        Comment parent = commentRepository.findById(parentId)
                .filter(c -> c.getNoteId().equals(noteId))
                .filter(c -> c.getParentId() == null)
                .filter(c -> c.getStatus() == CommentStatus.VISIBLE)
                .orElseThrow(() -> new IllegalArgumentException("回复的评论不存在或已隐藏"));
        return parent.getId();
    }

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
