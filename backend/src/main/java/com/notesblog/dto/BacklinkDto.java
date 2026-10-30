package com.notesblog.dto;

import java.time.LocalDateTime;

public record BacklinkDto(
        Long id,
        String title,
        String slug,
        String series,
        String snippet,
        LocalDateTime updatedAt,
        boolean isPublished) {
}
