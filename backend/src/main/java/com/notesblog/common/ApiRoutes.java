package com.notesblog.common;

/** 跨层共享的 API 路由契约。只放确实被多个层共同依赖的路径，避免变成字符串杂物间。 */
public final class ApiRoutes {

    /** 外部剪藏写入口：controller、security 放行规则、配置示例必须使用同一路由。 */
    public static final String INBOX_QUICK_CAPTURE = "/api/inbox/quick-capture";

    private ApiRoutes() {
    }
}
