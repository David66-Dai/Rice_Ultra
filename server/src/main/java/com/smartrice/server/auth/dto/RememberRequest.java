package com.smartrice.server.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RememberRequest(
	@NotBlank(message = "缺少记住登录令牌") @Size(max = 256) String rememberToken
) {
}
