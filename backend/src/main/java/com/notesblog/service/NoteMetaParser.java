package com.notesblog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 解析摘要/标签模型的自由文本输出。
 *
 * <p>模型常被要求输出 JSON，但实际会包代码围栏、解释性废话或少量脏字段。
 * 这里的职责是把这层不可靠文本变成受限的元数据候选；是否写入、是否覆盖用户内容、
 * 以及事务边界仍由 {@link NoteMetaService} 决定。
 */
@Component
public class NoteMetaParser {

    private static final int MAX_SUMMARY_CHARS = 160;
    private static final int MAX_TAGS = 6;
    private static final int MAX_TAG_CHARS = 32;

    private final ObjectMapper objectMapper;

    public NoteMetaParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析模型输出为摘要+标签；解析不出来按临时故障抛出，前端提示重试。 */
    Result parse(String raw) {
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
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
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
            return new Result(summary, List.copyOf(tags));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.TRANSIENT, "摘要标签解析失败", e);
        }
    }

    /** 解析结果：summary 可为 null（模型没给就保持原状），tags 可为空列表。 */
    record Result(String summary, List<String> tags) {
    }
}
