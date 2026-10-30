package com.notesblog.service;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.QuickCaptureRequest;
import com.notesblog.dto.QuickCaptureResponseDto;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InboxService {

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#([\\w\\u4e00-\\u9fa5_-]+)");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final NoteIndexService noteIndexService;
    private final AuditService auditService;

    @Value("${app.inbox.token:}")
    private String configuredToken;

    @Value("${security.jwt-secret:}")
    private String jwtSecret;

    @Value("${site.base-url:http://localhost:5173}")
    private String baseUrl;

    /**
     * 计算当前生效的剪藏授权 Token。
     * 优先使用显式配置的 {@code app.inbox.token}；未显式配置时，根据 jwtSecret 稳定派生安全 Token。
     */
    public String getEffectiveToken() {
        if (configuredToken != null && !configuredToken.isBlank()) {
            return configuredToken.trim();
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(("inbox-capture-salt:" + (jwtSecret == null ? "notes-blog-secret" : jwtSecret)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (Exception e) {
            return "inbox-" + Math.abs((jwtSecret == null ? "" : jwtSecret).hashCode());
        }
    }

    /**
     * 校验请求是否具备剪藏写入权限（支持已登录管理员、Bearer Token、X-Inbox-Token 头以及 Query Token）。
     */
    public boolean isAuthorized(String authHeader, String customTokenHeader, String queryToken, Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            return true;
        }
        String expected = getEffectiveToken();
        if (customTokenHeader != null && expected.equalsIgnoreCase(customTokenHeader.trim())) {
            return true;
        }
        if (queryToken != null && expected.equalsIgnoreCase(queryToken.trim())) {
            return true;
        }
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (expected.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行快捷捕捉 / 剪藏入库。
     */
    @Transactional
    public QuickCaptureResponseDto capture(QuickCaptureRequest request, String clientIp) {
        String rawContent = request.content() == null ? "" : request.content().trim();
        String title = extractTitle(rawContent);
        List<String> tags = extractTags(rawContent, request.tags());

        Note note = new Note();
        note.setTitle(title);
        note.setSlug("inbox-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        note.setContentMd(rawContent);
        note.setStatus(NoteStatus.DRAFT);
        note.setSourcePath("收件箱/" + sanitizeFilename(title) + ".md");
        note.setTags(resolveTags(tags));

        Note saved = noteRepository.save(note);
        noteIndexService.reindex(saved.getId());

        String sourceDesc = (request.source() != null && !request.source().isBlank())
                ? request.source().trim()
                : "API剪藏";
        auditService.recordAs("inbox-capture", "QUICK_CAPTURE", saved.getSlug(), sourceDesc + " [ip=" + clientIp + "]");
        log.info("inbox_quick_capture_saved id={} title='{}' source='{}'", saved.getId(), title, sourceDesc);

        List<String> tagNames = saved.getTags().stream().map(Tag::getName).toList();
        return new QuickCaptureResponseDto(
                saved.getId(),
                saved.getTitle(),
                saved.getSlug(),
                saved.getSourcePath(),
                tagNames,
                LocalDateTime.now()
        );
    }

    /**
     * 获取外部快捷捕捉工具配置与调用示例。
     */
    public Map<String, Object> getConfig() {
        String token = getEffectiveToken();
        String normalizedBase = baseUrl == null ? "http://localhost:8080" : baseUrl.replaceAll("/+$", "");
        String endpoint = normalizedBase + "/api/inbox/quick-capture";
        String curl = "curl -X POST '" + endpoint + "' \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -H 'X-Inbox-Token: " + token + "' \\\n"
                + "  -d '{\"content\": \"灵感记录 #闪念\", \"source\": \"终端测试\"}'";

        return Map.of(
                "token", token,
                "endpointUrl", endpoint,
                "curlExample", curl,
                "instruction", "可直接在 iOS 快捷指令、Raycast 或浏览器扩展中通过 POST 请求发送内容"
        );
    }

    public static String extractTitle(String content) {
        if (content == null || content.isBlank()) {
            return "闪念捕捉 " + LocalDateTime.now().format(TIME_FORMATTER);
        }
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // 清理 markdown 标题符、无序列表符、引用符
            String clean = trimmed.replaceAll("^[#\\-*+>\\d.\\s]+", "").trim();
            if (!clean.isEmpty()) {
                return clean.length() > 50 ? clean.substring(0, 50) + "…" : clean;
            }
        }
        return "闪念捕捉 " + LocalDateTime.now().format(TIME_FORMATTER);
    }

    public static List<String> extractTags(String content, List<String> explicitTags) {
        Set<String> tagSet = new LinkedHashSet<>();
        if (explicitTags != null) {
            explicitTags.stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(String::trim)
                    .forEach(tagSet::add);
        }
        if (content != null) {
            Matcher matcher = HASHTAG_PATTERN.matcher(content);
            while (matcher.find()) {
                String matched = matcher.group(1).trim();
                if (!matched.isEmpty() && matched.length() <= 30) {
                    tagSet.add(matched);
                }
            }
        }
        if (tagSet.isEmpty()) {
            tagSet.add("闪念");
        }
        return new ArrayList<>(tagSet);
    }

    private Set<Tag> resolveTags(List<String> names) {
        if (names == null || names.isEmpty()) return Set.of();
        Set<Tag> tags = new LinkedHashSet<>();
        for (String name : names) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            Tag tag = tagRepository.findByName(trimmed)
                    .orElseGet(() -> tagRepository.save(new Tag(trimmed)));
            tags.add(tag);
        }
        return tags;
    }

    private static String sanitizeFilename(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|\\s]", "-").replaceAll("-+", "-");
    }
}
