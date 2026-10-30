package com.notesblog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 读者与管理员搜索日志。
 *
 * <p>用于统计高频检索关键词以及「零结果未命中词」（未满足的选题需求）。
 */
@Entity
@Table(name = "search_logs")
@Getter
@Setter
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String query;

    @Column(name = "hit_count", nullable = false)
    private int hitCount = 0;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
