package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 模型输出解析：要求 JSON、容忍围栏和废话，超长截断、标签去重限量。 */
class NoteMetaServiceParseTest {

    @Test
    void parsesPlainJson() {
        NoteMetaService.NoteMeta meta = NoteMetaService.parseMeta(
                "{\"summary\":\"讲怎么部署到阿里云\",\"tags\":[\"部署\",\"docker\"]}");
        assertThat(meta.summary()).isEqualTo("讲怎么部署到阿里云");
        assertThat(meta.tags()).containsExactly("部署", "docker");
    }

    @Test
    void stripsCodeFenceAndPreamble() {
        NoteMetaService.NoteMeta meta = NoteMetaService.parseMeta(
                "好的，结果如下：\n```json\n{\"summary\":\"s\",\"tags\":[\"a\",\"b\",\"c\"]}\n```");
        assertThat(meta.summary()).isEqualTo("s");
        assertThat(meta.tags()).containsExactly("a", "b", "c");
    }

    @Test
    void clampsSummaryAndTags() {
        String longSummary = "长".repeat(300);
        NoteMetaService.NoteMeta meta = NoteMetaService.parseMeta(
                "{\"summary\":\"" + longSummary + "\","
                        + "\"tags\":[" + "\"t\",\"t2\",".repeat(10) + "\"末\"]}");
        assertThat(meta.summary()).hasSizeLessThanOrEqualTo(160);
        assertThat(meta.tags()).hasSizeLessThanOrEqualTo(6);
        assertThat(meta.tags()).doesNotContainNull();
    }

    @Test
    void dropsNonTextAndBlankTags() {
        NoteMetaService.NoteMeta meta = NoteMetaService.parseMeta(
                "{\"summary\":\"s\",\"tags\":[\"\", \"  \", 3, null, \"有效\"]}");
        assertThat(meta.tags()).containsExactly("有效");
    }

    @Test
    void keepsNullSummaryWhenMissing() {
        NoteMetaService.NoteMeta meta = NoteMetaService.parseMeta("{\"tags\":[\"a\"]}");
        assertThat(meta.summary()).isNull();
        assertThat(meta.tags()).containsExactly("a");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> NoteMetaService.parseMeta("这不是 JSON"))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> NoteMetaService.parseMeta(""))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> NoteMetaService.parseMeta(null))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void listIsImmutableCopy() {
        NoteMetaService.NoteMeta meta = NoteMetaService.parseMeta("{\"tags\":[\"a\"]}");
        assertThatThrownBy(() -> ((List<String>) meta.tags()).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
