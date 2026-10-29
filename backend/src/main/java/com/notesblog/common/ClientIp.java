package com.notesblog.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端 IP，登录限流与接口限流共用。
 *
 * <p>只认 nginx 写入的 {@code X-Real-IP}（它取自 {@code $remote_addr}），
 * 不解析 {@code X-Forwarded-For}——那个是「客户端值 + 追加」，首段可以被伪造。
 * 即便如此 IP 也不是可信身份，所以调用方还需要按账号维度的上限兜底。
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
