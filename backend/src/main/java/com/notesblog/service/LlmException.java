package com.notesblog.service;

/** 区分「可重试的临时故障」和「需要用户处理的配额/权限问题」。 */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** 额度用尽或超出速率限制：需要用户处理，重试无意义。 */
        QUOTA,
        /** 网络抖动、服务端错误：可以重试。 */
        TRANSIENT
    }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
