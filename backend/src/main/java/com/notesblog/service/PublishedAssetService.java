package com.notesblog.service;

import com.notesblog.domain.NoteStatus;
import com.notesblog.repository.NoteRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

/**
 * 已发布笔记引用的资产路径集合（内存缓存）。
 *
 * <p>{@code GET /api/assets/**} 是 permitAll，而迁移图片的路径是 Obsidian 的
 * 相对路径、可预测性高——不做限制的话，未公开笔记里的截图也能被知道路径的人
 * 直接读走。这里从全部已发布笔记的正文里抽出资产引用，形成白名单：
 * 匿名请求只放行名单内的路径，登录用户不受限（编辑页要看自己的全部图）。
 *
 * <p>刷新是懒式的 + TTL：笔记保存/删除的入口很多（单个、批量、同步导入），
 * 逐处挂钩容易漏，所以统一按 30 秒过期重算。代价是「取消发布后最多 30 秒内
 * 图片仍可读」——对这个威胁模型（防枚举直链，不是防实时泄密）完全够用。
 * 680 篇 × 平均几 KB 的全量重算在毫秒级，30 秒一次可以忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishedAssetService {

    /**
     * 正文里的资产引用形如 {@code /api/assets/Java/Java基础/assets/Ajax-1.png}
     * （路径段 URL 编码）。终止于空白、右括号、右方括号或引号。
     */
    private static final Pattern ASSET_REF =
            Pattern.compile("/api/assets/([^\\s)\\]\"'<]+)");

    private static final Duration TTL = Duration.ofSeconds(30);

    private final NoteRepository noteRepository;

    private volatile Set<String> paths = Set.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    /** 匿名请求是否可以读这个相对路径（URL 解码后的）。 */
    public boolean isPubliclyReadable(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        maybeRefresh();
        return paths.contains(relativePath);
    }

    /** 主动刷新（目前只有测试和启动用；运行期靠 TTL 懒刷新）。 */
    public synchronized void refresh() {
        List<String> contents = noteRepository.findContentMdByStatus(NoteStatus.PUBLISHED);
        Set<String> next = new HashSet<>();
        for (String content : contents) {
            if (content == null || content.isEmpty()) {
                continue;
            }
            Matcher matcher = ASSET_REF.matcher(content);
            while (matcher.find()) {
                try {
                    // 控制器读文件前用同样的方式解码，两边必须一致
                    next.add(UriUtils.decode(matcher.group(1), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    // 非法编码的引用本来也读不出来，跳过即可
                }
            }
        }
        paths = Set.copyOf(next);
        loadedAt = Instant.now();
        log.info("published_assets_refreshed count={}", paths.size());
    }

    private void maybeRefresh() {
        if (Instant.now().isAfter(loadedAt.plus(TTL))) {
            refresh();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }
}
