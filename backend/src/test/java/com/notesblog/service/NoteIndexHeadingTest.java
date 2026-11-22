package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * note_chunks.heading 是 VARCHAR(255)，而正文里有小节标题被写成了整段（一行 `#` 后面跟几百字）。
 * 不截断的话整篇索引会 Data too long 插不进去，补建时表现为同一篇反复失败。
 */
class NoteIndexHeadingTest {

    @Test
    @DisplayName("不超过 255 字符的标题原样保留")
    void shortHeadingIsKept() {
        String heading = "高斯噪声是怎么定义的";
        assertThat(NoteIndexService.trimHeading(heading)).isEqualTo(heading);
        assertThat(NoteIndexService.trimHeading(null)).isNull();
    }

    @Test
    @DisplayName("超长标题截到 255 字符并以省略号收尾")
    void longHeadingIsTruncated() {
        String heading = "长".repeat(400);
        String trimmed = NoteIndexService.trimHeading(heading);

        assertThat(trimmed).hasSize(255).endsWith("…");
        assertThat(trimmed).startsWith("长长长");
    }

    @Test
    @DisplayName("截断不会把 emoji 劈成半个字符")
    void truncationKeepsSurrogatePairsIntact() {
        // 254 个 'a' 之后才是 emoji，边界正好落在它的高位代理上
        String heading = "a".repeat(254) + "😀" + "b".repeat(50);
        String trimmed = NoteIndexService.trimHeading(heading);

        assertThat(trimmed).hasSize(255).endsWith("…");
        assertThat(trimmed.codePointCount(0, trimmed.length() - 1))
                .isEqualTo(trimmed.length() - 1); // 去掉省略号后没有落单的代理字符
    }
}