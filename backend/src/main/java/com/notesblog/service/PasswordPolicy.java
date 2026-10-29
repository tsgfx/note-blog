package com.notesblog.service;

import java.nio.charset.StandardCharsets;

/**
 * 密码强度规则。抛 {@link IllegalArgumentException}，由全局异常处理转成 400。
 *
 * <p>上限按 <b>UTF-8 字节</b>而不是字符数：BCrypt 只取前 72 字节，
 * 30 个汉字 = 90 字节会被静默截断，导致两个不同的长密码互相等价。
 * {@code @Size(max = 72)} 数的是字符，挡不住这种情况。
 */
public final class PasswordPolicy {

    private static final int MIN_CHARS = 8;
    private static final int MAX_BCRYPT_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String raw) {
        if (raw == null || raw.length() < MIN_CHARS) {
            throw new IllegalArgumentException("新密码至少 " + MIN_CHARS + " 位");
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            throw new IllegalArgumentException("新密码过长，最多 72 字节（约 24 个汉字）");
        }
    }
}
