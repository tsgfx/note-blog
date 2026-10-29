package com.notesblog.dto;

import java.util.Map;

/**
 * 健康状态。
 *
 * @param status     整体状态：{@code up} 表示依赖全通，{@code degraded} 表示有依赖不可用
 * @param components 各依赖的明细，例如 {@code {"db": true, "embedding": false}}
 * @param version    部署版本（构建时注入的短 commit），{@code dev} 表示本地开发
 * @param time       检查时间
 */
public record HealthReport(String status, Map<String, Boolean> components, String version, String time) {

    public static final String UP = "up";
    public static final String DEGRADED = "degraded";
}
