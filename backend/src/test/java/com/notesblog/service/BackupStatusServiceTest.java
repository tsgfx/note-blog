package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 备份状态的判定。
 *
 * <p>要守住的其实是四件事：目录不存在时别抛异常、按最新一份算时间、
 * 超过 48 小时算过期、失败标记只在它比成功更新时才提示（否则早就修好了还一直报警）。
 */
class BackupStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path dir;

    private BackupStatusService service() {
        return new BackupStatusService(dir, clock);
    }

    private void dump(String name, Duration age, int bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(age)));
    }

    @Test
    @DisplayName("目录不存在时降级为「不可用且过期」，不抛异常")
    void missingDirectory() {
        BackupStatusService service = new BackupStatusService(dir.resolve("not-there"), clock);
        var status = service.status();

        assertThat(status.available()).isFalse();
        assertThat(status.stale()).isTrue();
        assertThat(status.lastSuccessAt()).isNull();
    }

    @Test
    @DisplayName("按最新一份备份算时间与体积，并统计份数")
    void reportsNewestBackup() throws IOException {
        dump("notes_blog_20261021_030000.sql.gz", Duration.ofHours(33), 100);
        dump("notes_blog_20261023_030000.sql.gz", Duration.ofHours(9), 300);
        dump("storage_20261023_030000.tar.gz", Duration.ofHours(9), 999);

        var status = service().status();

        assertThat(status.available()).isTrue();
        assertThat(status.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(9)));
        assertThat(status.lastSuccessSize()).isEqualTo(300);
        assertThat(status.copies()).isEqualTo(2);
        assertThat(status.stale()).isFalse();
    }

    @Test
    @DisplayName("超过 48 小时没成功备份就标记过期")
    void becomesStale() throws IOException {
        dump("notes_blog_20261021_030000.sql.gz", Duration.ofHours(49), 100);

        assertThat(service().status().stale()).isTrue();
    }

    @Test
    @DisplayName("失败标记比最近一次成功更新时才提示")
    void errorMarkerReportedOnlyWhenNewer() throws IOException {
        dump("notes_blog_20261023_030000.sql.gz", Duration.ofHours(9), 100);
        Path marker = dir.resolve(".last-backup-error");
        Files.writeString(marker, "备份失败于 2026-10-23 10:00:00，详见日志\n");
        Files.setLastModifiedTime(marker, FileTime.from(NOW.minus(Duration.ofHours(2))));

        var status = service().status();
        assertThat(status.lastErrorAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(status.lastErrorMessage()).contains("备份失败");

        // 把标记改到比成功更旧：说明之后已经备成功了，不该再报错
        Files.setLastModifiedTime(marker, FileTime.from(NOW.minus(Duration.ofHours(12))));
        assertThat(service().status().lastErrorMessage()).isNull();
    }
}
