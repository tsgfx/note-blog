package com.notesblog.dto;

import java.time.LocalDateTime;
import java.util.List;

public record QuickCaptureResponseDto(
        Long id,
        String title,
        String slug,
        String sourcePath,
        List<String> tags,
        LocalDateTime createdAt) {
}
