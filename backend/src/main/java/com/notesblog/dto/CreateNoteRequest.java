package com.notesblog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateNoteRequest(
        @NotBlank(message = "标题不能为空") @Size(max = 255, message = "标题不能超过 255 个字符") String title,
        @Size(max = 500, message = "摘要不能超过 500 个字符") String summary,
        @NotBlank(message = "正文不能为空") String contentMd,
        @Size(max = 30, message = "标签不能超过 30 个") List<@NotBlank String> tags) {
}
