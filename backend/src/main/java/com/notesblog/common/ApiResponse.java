package com.notesblog.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应体：{ code, message, data }，code=0 表示成功。
 */
@Getter
@AllArgsConstructor
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
