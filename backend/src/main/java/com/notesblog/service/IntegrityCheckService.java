package com.notesblog.service;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.IntegrityReportDto;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库与静态资产完整性自检。
 *
 * <p>定期或管理员按需排查：
 * 1. 已发布文章中引用的本地图片是否存在丢失（404 资产死链）；
 * 2. 是否存在正文为空的废弃笔记；
 * 3. 是否存在未生成分片向量的笔记（语义检索盲区）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityCheckService {

    /** 匹配 Markdown 图片语法中指向 /api/assets/ 的本地相对路径 */
    private static final Pattern ASSET_IMG_PATTERN = Pattern.compile("!\\[[^\\]]*\\]\\(/api/assets/([^)\\s]+)\\)");

    private final NoteRepository noteRepository;
    private final NoteService noteService;
    private final AssetStorage assetStorage;

    @Transactional(readOnly = true)
    public IntegrityReportDto check() {
        List<Note> allNotes = noteRepository.findAll();
        long publishedCount = 0;
        long draftCount = 0;
        long emptyCount = 0;
        List<IntegrityReportDto.BrokenAssetItem> brokenAssets = new ArrayList<>();

        for (Note note : allNotes) {
            boolean isPublished = note.getStatus() == NoteStatus.PUBLISHED;
            if (isPublished) {
                publishedCount++;
            } else {
                draftCount++;
            }

            String content = note.getContentMd();
            if (content == null || content.isBlank()) {
                emptyCount++;
                continue;
            }

            // 只对公开文章做死链报警（草稿中的临时编辑不惊扰作者）
            if (isPublished) {
                Matcher matcher = ASSET_IMG_PATTERN.matcher(content);
                while (matcher.find()) {
                    String assetPath = matcher.group(1);
                    if (!assetStorage.exists(assetPath)) {
                        brokenAssets.add(new IntegrityReportDto.BrokenAssetItem(
                                note.getId(), note.getTitle(), note.getSlug(), assetPath));
                    }
                }
            }
        }

        long missingChunks = noteService.countMissingChunks();

        return new IntegrityReportDto(
                allNotes.size(),
                publishedCount,
                draftCount,
                missingChunks,
                emptyCount,
                brokenAssets);
    }
}
