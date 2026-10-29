package com.notesblog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写完笔记后自动生成摘要与标签。
 *
 * <p>策略：默认只填空白字段（不覆盖用户手写的内容）；前端「重新生成」按钮
 * 传 force=true 时两者都重写。LLM 输出强制要求 JSON，解析失败视为临时故障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteMetaService {

    /** 只把正文开头这么多字符交给模型：摘要和标签不需要全文，也省额度。 */
    private static final int MAX_CONTENT_CHARS = 4000;
    private static final int MAX_SUMMARY_CHARS = 160;
    private static final int MAX_TAGS = 6;
    private static final int MAX_TAG_CHARS = 32;

    private static final String SYSTEM_PROMPT = """
            你是个人笔记库的整理助手。阅读给定的笔记标题和正文，生成一句话摘要和 3-6 个主题标签。
            只输出一个 JSON 对象，格式：{"summary":"…","tags":["…","…"]}，不要输出 JSON 以外的任何内容。
            摘要不超过 60 字，概括笔记核心内容；标签用简短词（专有名词可保留英文），不要照抄标题。""";

    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final LlmStreamingClient llmClient;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Transactional
    public NoteDetailDto generate(Long id, boolean force) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("笔记不存在：" + id));
        boolean needSummary = force || isBlank(note.getSummary());
        boolean needTags = force || note.getTags().isEmpty();
        if (!needSummary && !needTags) {
            // 都有内容且不强制：不花额度，直接原样返回
            return NoteDetailDto.from(note);
        }

        String raw = collectLlm(note.getTitle(), note.getContentMd());
        NoteMeta meta = parseMeta(raw);

        boolean changed = false;
        if (needSummary && meta.summary() != null && !meta.summary().isBlank()) {
            note.setSummary(meta.summary());
            changed = true;
        }
        if (needTags && !meta.tags().isEmpty()) {
            note.setTags(resolveTags(meta.tags()));
            changed = true;
        }
        // 已发布的文章补摘要/标签同样会立刻改变线上展示（博客列表就显示摘要），
        // 与「改了正文」是同一件事，所以一并打上「待重发」标记。
        if (changed && note.getStatus() == NoteStatus.PUBLISHED) {
            note.setHasUnpublishedEdits(true);
        }
        Note saved = noteRepository.save(note);
        auditService.record(force ? "NOTE_META_REGEN" : "NOTE_META_AUTO", saved.getSlug(), saved.getTitle());
        return NoteDetailDto.from(saved);
    }

    private String collectLlm(String title, String contentMd) {
        StringBuilder out = new StringBuilder();
        String user = "笔记标题：" + (title == null ? "（无标题）" : title)
                + "\n\n笔记正文：\n" + truncate(contentMd, MAX_CONTENT_CHARS);
        llmClient.stream(SYSTEM_PROMPT, user, out::append);
        return out.toString();
    }

    /** 解析模型输出为摘要+标签；解析不出来按临时故障抛出，前端提示重试。 */
    static NoteMeta parseMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmException(LlmException.Kind.TRANSIENT, "模型没有返回内容");
        }
        String text = raw.trim();
        // 有些模型爱包一层 ```json 围栏，剥掉
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException(LlmException.Kind.TRANSIENT, "模型返回的不是 JSON");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(text.substring(start, end + 1));
            String summary = root.path("summary").isTextual()
                    ? root.path("summary").asText().trim() : null;
            if (summary != null && summary.length() > MAX_SUMMARY_CHARS) {
                summary = summary.substring(0, MAX_SUMMARY_CHARS);
            }
            Set<String> tags = new LinkedHashSet<>();
            if (root.path("tags").isArray()) {
                for (JsonNode tag : root.path("tags")) {
                    if (!tag.isTextual()) continue;
                    String name = tag.asText().trim();
                    if (name.isEmpty()) continue;
                    if (name.length() > MAX_TAG_CHARS) name = name.substring(0, MAX_TAG_CHARS);
                    tags.add(name);
                    if (tags.size() >= MAX_TAGS) break;
                }
            }
            return new NoteMeta(summary, List.copyOf(tags));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.TRANSIENT, "摘要标签解析失败", e);
        }
    }

    private Set<Tag> resolveTags(java.util.List<String> names) {
        LinkedHashSet<Tag> tags = new LinkedHashSet<>();
        for (String name : names) {
            tags.add(tagRepository.findByName(name).orElseGet(() -> {
                Tag tag = new Tag();
                tag.setName(name);
                return tagRepository.save(tag);
            }));
        }
        return tags;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 解析结果：summary 可为 null（模型没给就保持原状），tags 可为空列表。 */
    record NoteMeta(String summary, java.util.List<String> tags) {
    }
}
