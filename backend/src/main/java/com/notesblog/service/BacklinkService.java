package com.notesblog.service;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.BacklinkDto;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 双向链接（Backlinks / 反向链接）服务。
 *
 * <p>识别正文中通过 Markdown 链接（如 {@code [text](/blog/slug)}）、Wiki-link（{@code [[标题]]}）
 * 或 URL 直接引用当前文章的其他笔记，并提取引用处的上下文片段。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BacklinkService {

    private final NoteRepository noteRepository;

    /**
     * 面向公开读者的反向链接：仅检索已发布的引用文章。
     */
    public List<BacklinkDto> findBacklinksForPublic(String slug) {
        if (slug == null || slug.isBlank()) {
            return List.of();
        }
        Note target = noteRepository.findBySlug(slug)
                .filter(n -> n.getStatus() == NoteStatus.PUBLISHED)
                .orElse(null);
        if (target == null) {
            return List.of();
        }
        return findBacklinks(target, NoteStatus.PUBLISHED);
    }

    /**
     * 面向作者管理端的反向链接：包含草稿与已发布笔记。
     */
    public List<BacklinkDto> findBacklinksForAdmin(Long id) {
        if (id == null) {
            return List.of();
        }
        Note target = noteRepository.findById(id).orElse(null);
        if (target == null) {
            return List.of();
        }
        return findBacklinks(target, null);
    }

    private List<BacklinkDto> findBacklinks(Note target, NoteStatus status) {
        String slug = target.getSlug();
        String title = target.getTitle();

        List<Note> candidates = noteRepository.findBacklinksCandidates(
                target.getId(),
                status,
                slug,
                title,
                PageRequest.of(0, 30)
        );

        List<BacklinkDto> backlinks = new ArrayList<>();
        for (Note candidate : candidates) {
            String content = candidate.getContentMd();
            if (content == null || content.isBlank()) {
                continue;
            }

            String snippet = extractSnippet(content, slug, title);
            if (snippet != null) {
                String series = extractSeries(candidate);
                backlinks.add(new BacklinkDto(
                        candidate.getId(),
                        candidate.getTitle(),
                        candidate.getSlug(),
                        series,
                        snippet,
                        candidate.getUpdatedAt(),
                        candidate.getStatus() == NoteStatus.PUBLISHED
                ));
            }
        }
        return backlinks;
    }

    /**
     * 从引用文章的正文中定位引用处，并截取前后上下文作为摘要。
     */
    public String extractSnippet(String content, String slug, String title) {
        if (content == null) return null;

        int pos = -1;
        int matchLen = 0;

        // 1. 优先定位 slug 引用（如 /blog/slug 或直接 slug）
        if (slug != null && !slug.isBlank()) {
            pos = content.indexOf(slug);
            if (pos >= 0) {
                matchLen = slug.length();
            }
        }

        // 2. 其次定位 Wiki-link 引用 [[标题]]
        if (pos < 0 && title != null && !title.isBlank()) {
            String wikiPattern = "[[" + title;
            pos = content.indexOf(wikiPattern);
            if (pos >= 0) {
                matchLen = wikiPattern.length();
            }
        }

        // 3. 再次定位普通链接文本中的标题 [标题](
        if (pos < 0 && title != null && !title.isBlank()) {
            String mdLinkPattern = "[" + title + "]";
            pos = content.indexOf(mdLinkPattern);
            if (pos >= 0) {
                matchLen = mdLinkPattern.length();
            }
        }

        // 4. 最后兜底直接匹配标题（仅在具有链接语义标记时）
        if (pos < 0 && title != null && title.length() >= 3) {
            pos = content.indexOf(title);
            if (pos >= 0) {
                matchLen = title.length();
            }
        }

        if (pos < 0) {
            return null;
        }

        // 向前向后截取上下文（前后各约 60 字符）
        int start = Math.max(0, pos - 60);
        int end = Math.min(content.length(), pos + matchLen + 80);

        String rawSnippet = content.substring(start, end)
                .replaceAll("[\r\n]+", " ")
                .trim();

        // 弱化大段 markdown 符号，保持可读性
        String cleaned = rawSnippet
                .replaceAll("[#*`>]", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (start > 0) {
            cleaned = "…" + cleaned;
        }
        if (end < content.length()) {
            cleaned = cleaned + "…";
        }

        return cleaned;
    }

    /**
     * 提取笔记所在的专栏或所属系列名称。
     */
    private static String extractSeries(Note note) {
        if (note == null) return null;
        if (note.getTags() != null) {
            for (Tag tag : note.getTags()) {
                String name = tag.getName();
                if (name != null && (name.startsWith("专栏:") || name.startsWith("系列:"))) {
                    return name.replaceFirst("^(专栏|系列):", "").trim();
                }
            }
        }
        String source = note.getSourcePath();
        if (source != null) {
            String[] parts = source.split("/");
            if (parts.length >= 2) {
                String candidate = parts[parts.length - 2];
                if (!candidate.isBlank() && !List.of("assets", "images", "img").contains(candidate.toLowerCase())) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
