package com.smartrice.server.ai;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record AiAnalysisRequest(
	@NotNull @Pattern(regexp = "S(?:0[1-9]|10)") String stationId,
	@NotNull LocalDate date,
	int windowDays,
	@Pattern(regexp = "unknown|seedling|tillering|jointing|booting|heading|filling|mature") String growthStage
) {}
