package com.notesblog.service;

import com.notesblog.dto.BackupStatusDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读备份目录，得出「最近一次成功备份是什么时候」。
 *
 * <p>为什么需要它：{@code scripts/backup.sh} 本身是可用的，但没有任何东西保证它在跑。
 * 备份真正的风险不是"备不出来"，而是**三个月后才发现从没成功过**。
 * 所以这里把目录里的文件时间变成设置页上一条可见的提示。
 *
 * <p>只读文件元数据（mtime / size），不解析备份内容——解析 22MB 的 gzip 没必要，
 * 而且 gzip 能不能解开是恢复演练该管的事，不是状态页该管的事。
 */
@Slf4j
@Service
public class BackupStatusService {

    /** 超过这个时长没成功备份就算过期，设置页会亮黄条 */
    static final Duration STALE_AFTER = Duration.ofHours(48);

    private static final String DB_PREFIX = "notes_blog_";
    private static final String DB_SUFFIX = ".sql.gz";
    private static final String ERROR_MARKER = ".last-backup-error";

    private final Path dir;
    private final Clock clock;

    @Autowired
    public BackupStatusService(@Value("${app.backup.dir:/data/backups}") String dir) {
        this(Path.of(dir), Clock.systemUTC());
    }

    /** 供测试注入临时目录与固定时钟 */
    BackupStatusService(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public BackupStatusDto status() {
        if (!Files.isDirectory(dir)) {
            return new BackupStatusDto(false, dir.toString(), null, 0, 0, null, null, true);
        }

        List<Path> dumps = listDumps();
        Path newest = dumps.stream().max(Comparator.comparingLong(this::modifiedAt)).orElse(null);

        Instant lastSuccessAt = newest == null ? null : Instant.ofEpochMilli(modifiedAt(newest));
        long size = newest == null ? 0 : sizeOf(newest);
        String errorMessage = null;
        Instant errorAt = null;

        Path marker = dir.resolve(ERROR_MARKER);
        if (Files.isRegularFile(marker)) {
            Instant markerAt = Instant.ofEpochMilli(modifiedAt(marker));
            // 标记比最近一次成功更旧，说明后来已经备成功了，这条失败不再提示
            if (lastSuccessAt == null || markerAt.isAfter(lastSuccessAt)) {
                errorAt = markerAt;
                errorMessage = readFirstLine(marker);
            }
        }

        boolean stale = lastSuccessAt == null || Duration.between(lastSuccessAt, clock.instant()).compareTo(STALE_AFTER) > 0;
        return new BackupStatusDto(true, dir.toString(), lastSuccessAt, size, dumps.size(), errorAt, errorMessage, stale);
    }

    private List<Path> listDumps() {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(DB_PREFIX) && name.endsWith(DB_SUFFIX);
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("读取备份目录失败：{}", dir, e);
            return List.of();
        }
    }

    private long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String readFirstLine(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8).strip();
            int newline = text.indexOf('\n');
            String line = newline < 0 ? text : text.substring(0, newline);
            return line.length() > 300 ? line.substring(0, 300) + "…" : line;
        } catch (IOException e) {
            log.warn("读取备份失败标记失败：{}", path, e);
            return "（失败原因读取不到，检查备份目录权限）";
        }
    }
}
