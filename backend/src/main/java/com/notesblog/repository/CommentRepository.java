package com.notesblog.repository;

import com.notesblog.domain.Comment;
import com.notesblog.domain.CommentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 公开接口用：只取 VISIBLE，按时间正序（评论按阅读顺序从上往下长）。
     *
     * <p>用 Page 而不是返回全部：一篇热门文章可能攒下几百条，
     * 一次全吐给浏览器会让公开页变慢，也没人真的从第一条读到最后一条。
     */
    Page<Comment> findByNoteIdAndStatusOrderByCreatedAtAsc(Long noteId, CommentStatus status, Pageable pageable);

    /**
     * 顶层评论分页：回复不占条数，跟着它的父评论一起出现。
     *
     * <p>否则一条被回复三次的评论会把整页挤满，翻页也会一半是回复。
     */
    Page<Comment> findByNoteIdAndStatusAndParentIdIsNullOrderByCreatedAtAsc(
            Long noteId, CommentStatus status, Pageable pageable);

    /** 某一页顶层评论对应的回复，一次查齐，避免逐条再查一次。 */
    List<Comment> findByParentIdInAndStatusOrderByCreatedAtAsc(
            Collection<Long> parentIds, CommentStatus status);

    /** 管理端用：不管状态全给，最新在前。 */
    Page<Comment> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
