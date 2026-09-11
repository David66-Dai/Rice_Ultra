package com.smartrice.server.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank(message = "请输入账号") @Size(max = 64, message = "账号过长") String username,
	@NotBlank(message = "请输入密码") @Size(max = 128, message = "密码过长") String password,
	boolean rememberMe
) {
}
