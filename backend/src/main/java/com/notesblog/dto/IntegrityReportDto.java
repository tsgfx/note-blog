package com.notesblog.dto;

import java.util.List;

public record IntegrityReportDto(
        long totalNotes,
        long publishedNotes,
        long draftNotes,
        long missingChunksNotes,
        long emptyNotes,
        List<BrokenAssetItem> brokenAssets) {

    public record BrokenAssetItem(Long noteId, String noteTitle, String noteSlug, String assetPath) {}
}
