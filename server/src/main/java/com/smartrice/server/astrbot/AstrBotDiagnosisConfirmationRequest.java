package com.smartrice.server.astrbot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import jakarta.validation.constraints.Size;

public record AstrBotDiagnosisConfirmationRequest(
	@NotNull @Valid AstrBotIdentity identity,
	@NotNull UUID confirmationId,
	@Size(max = 1000) String originalText
) {
}
