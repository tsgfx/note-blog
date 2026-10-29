package com.notesblog.repository;

import com.notesblog.domain.Comment;
import com.notesblog.domain.CommentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 公开接口用：只取 VISIBLE，按时间正序（评论按阅读顺序从上往下长）。
     *
     * <p>用 Page 而不是返回全部：一篇热门文章可能攒下几百条，
     * 一次全吐给浏览器会让公开页变慢，也没人真的从第一条读到最后一条。
     */
    Page<Comment> findByNoteIdAndStatusOrderByCreatedAtAsc(Long noteId, CommentStatus status, Pageable pageable);

    /** 管理端用：不管状态全给，最新在前。 */
    Page<Comment> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
