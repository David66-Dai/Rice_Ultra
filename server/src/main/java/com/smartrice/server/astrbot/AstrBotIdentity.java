package com.smartrice.server.astrbot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AstrBotIdentity(
	@NotBlank @Size(max = 512) String umo,
	@NotBlank @Size(max = 256) String senderId
) {
}
