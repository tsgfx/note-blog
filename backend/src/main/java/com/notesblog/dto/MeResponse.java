package com.notesblog.dto;

/**
 * 当前登录者的身份摘要。
 *
 * <p>只放前端「我是不是管理员」需要的最小信息：不放 {@code passwordHash}、{@code tokenVersion}，
 * 也不回显任何令牌。{@code displayName} 在库里可为空，而 {@code application.yml} 开了
 * {@code default-property-inclusion: non_null}，所以为 null 时该字段整个不出现，前端按可选处理。
 */
public record MeResponse(String username, String displayName, String role) {}
