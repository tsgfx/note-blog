package com.notesblog.dto;

import java.time.Instant;

/**
 * 备份状态，给设置页显示「多久没备份成功」用。
 *
 * @param available        备份目录是否可读（容器里是 {@code ./backups} 的只读挂载）
 * @param dir              实际检查的目录，排查时先看它对不对
 * @param lastSuccessAt    最近一次成功备份的时间；从未成功过为 {@code null}
 * @param lastSuccessSize  那次数据库备份文件的字节数
 * @param copies           现存数据库备份份数
 * @param lastErrorAt      最近一次失败的时间；没有失败标记则为 {@code null}
 * @param lastErrorMessage 失败原因（脚本写入的原文）
 * @param stale            是否已经超过阈值没成功备份——这一条才是要亮红的地方
 */
public record BackupStatusDto(
        boolean available,
        String dir,
        Instant lastSuccessAt,
        long lastSuccessSize,
        int copies,
        Instant lastErrorAt,
        String lastErrorMessage,
        boolean stale) {
}
