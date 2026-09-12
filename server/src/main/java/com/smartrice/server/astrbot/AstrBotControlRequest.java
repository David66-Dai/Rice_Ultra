package com.smartrice.server.astrbot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AstrBotControlRequest(
	@NotNull @Valid AstrBotIdentity identity,
	@NotNull UUID requestId,
	@NotBlank @Size(max = 8) String stationId,
	@NotBlank @Size(max = 16) String device,
	@NotNull Boolean enabled,
	@NotNull @Min(0) Long expectedRevision,
	@Min(1) Integer durationSeconds,
	boolean confirmed,
	boolean testMode,
	@Size(max = 1000) String originalText
) {
}
