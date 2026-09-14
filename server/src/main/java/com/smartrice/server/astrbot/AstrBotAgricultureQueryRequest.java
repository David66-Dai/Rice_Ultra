package com.smartrice.server.astrbot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AstrBotAgricultureQueryRequest(
	@NotNull @Valid AstrBotIdentity identity,
	@Size(max = 16) String stationId,
	@Size(max = 10) String startDate,
	@Size(max = 10) String endDate
) {
}
