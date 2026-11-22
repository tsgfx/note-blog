package com.notesblog.service;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.QuickCaptureRequest;
import com.notesblog.dto.QuickCaptureResponseDto;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Value("${site.base-url:http://localhost:5173}")
    private String baseUrl;

    /**
     * 当前生效的剪藏授权 Token：只认显式配置的 {@code app.inbox.token}。
     *
     * <p><b>以前这里会在未配置时用 {@code jwtSecret} 派生一个</b>，那是坏设计：
     * 派生值等于 JWT 签名密钥的哈希，一旦这串 token 出现在快捷指令、浏览器历史、
     * nginx 日志里（它曾经还能走 {@code ?token=}），就等于把签发令牌的钥匙交出去一半；
     * 而且「没配置也能用」让漏配配置变成静默成功。现在未配置 = 关闭，只有已登录管理员还能写。
     */
    public String getEffectiveToken() {
        return configuredToken == null ? "" : configuredToken.trim();
    }

    /** 剪藏功能是否已配置外部 Token。 */
    public boolean isTokenConfigured() {
        return !getEffectiveToken().isEmpty();
    }

    /**
     * 校验请求是否具备剪藏写入权限：已登录管理员，或带 {@code X-Inbox-Token} / Bearer Token 的调用方。
     *
     * <p>不再接受 {@code ?token=}：查询串会进 nginx access log、浏览器历史和 Referer，
     * 等于把长期有效的凭写权限的密钥写进日志。
     */
    public boolean isAuthorized(String authHeader, String customTokenHeader, Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            return true;
        }
        String expected = getEffectiveToken();
        if (expected.isEmpty()) {
            // 未配置 = 关掉外部写入口，而不是退回到某个可推导的默认值
            return false;
        }
        if (customTokenHeader != null && expected.equalsIgnoreCase(customTokenHeader.trim())) {
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

    /** 队列满只记告警：正文已入库，分片可以事后一键补建。 */
    private void queueReindex(Long noteId) {
        try {
            noteIndexService.reindex(noteId);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("inbox_reindex_queue_full noteId={}", noteId);
        }
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
        // 提交后再排索引：事务还没提交时重建线程按 id 查不到这一行（见 NoteService.reindexAfterCommit）
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    queueReindex(saved.getId());
                }
            });
        } else {
            queueReindex(saved.getId());
        }

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
        String normalizedBase = baseUrl == null ? "http://localhost:8080" : baseUrl.replaceAll("/+$", "");
        String endpoint = normalizedBase + "/api/inbox/quick-capture";
        if (!isTokenConfigured()) {
            // 不再退回派生值：没配置就明确告诉用户去配，而不是给一个「看起来能用」的串
            return Map.of(
                    "token", "",
                    "configured", false,
                    "endpointUrl", endpoint,
                    "curlExample", "",
                    "instruction", "未启用外部剪藏：请在服务器 .env 设置 INBOX_TOKEN（建议 32 位随机串）后重启，"
                            + "再用 X-Inbox-Token 请求头调用；现在只能登录后在编辑器里写。"
            );
        }
        String token = getEffectiveToken();
        String curl = "curl -X POST '" + endpoint + "' \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -H 'X-Inbox-Token: " + token + "' \\\n"
                + "  -d '{\"content\": \"灵感记录 #闪念\", \"source\": \"终端测试\"}'";

        return Map.of(
                "token", token,
                "configured", true,
                "endpointUrl", endpoint,
                "curlExample", curl,
                "instruction", "可直接在 iOS 快捷指令、Raycast 或浏览器扩展中通过 POST 请求发送内容；Token 只放请求头，不要用 ?token="
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
