package com.notesblog.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record QuickCaptureRequest(
        @NotBlank(message = "内容不能为空") String content,
        String source,
        List<String> tags) {
}
