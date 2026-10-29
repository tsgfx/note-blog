package com.notesblog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码的入参。强度校验不放在注解上，统一交给 {@code PasswordPolicy}，
 * 这样"至少 8 位"只有一处权威实现。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "请输入当前密码") String currentPassword,
        @NotBlank(message = "请输入新密码")
        @Size(max = 72, message = "新密码过长")
        String newPassword) {
}
