package com.notesblog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 游客发评论。
 *
 * @param content  评论正文。上限 2000 字符，与 {@code comments.content} 列宽一致。
 * @param honeypot 蜜罐字段：CSS 隐藏，正常用户看不见也不会填。
 *                 字段名刻意叫 {@code website} 这类「表单里常见但不该由人填」的名字，
 *                 机器人填表时常把每个 input 都塞一遍，于是必然中招。
 *                 用 {@code Boolean} 而不是 String：前端只在被填时传 true，省得判断空串。
 */
public record CreateCommentRequest(
        @NotBlank(message = "评论内容不能为空")
        @Size(max = 2000, message = "评论不能超过 2000 个字符")
        String content,
        Boolean website) {

    /** 蜜罐被填了没有。null 视为没填（正常请求不带这个字段）。 */
    public boolean honeypotFilled() {
        return Boolean.TRUE.equals(website);
    }
}
