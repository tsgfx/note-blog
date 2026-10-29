package com.notesblog.domain;

/**
 * 评论的展示状态。
 *
 * <p>只有两种，刻意不做「待审核」：用户要求「没有专门设置隐藏或删除，评论默认可见」，
 * 默认就 {@link #VISIBLE}。审核队列会让游客的评论石沉大海且自己不知道，
 * 而管理员真正需要的是「事后能撤下」而不是「事先逐条放行」。
 */
public enum CommentStatus {
    /** 公开可见（默认） */
    VISIBLE,
    /** 仅管理员可见：内容仍保留，公开接口不再返回 */
    HIDDEN
}
