package com.smartrice.server.realtime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PreventionPolicyUpdateRequest(
	@NotNull Boolean requireAstrBotConfirmation,
	@NotNull @Min(0) Long expectedRevision
) {
}
