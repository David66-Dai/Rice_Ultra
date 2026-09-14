package com.smartrice.server.astrbot;

import com.smartrice.server.pest.PestDiseaseSummary;
import java.time.LocalDate;
import java.util.List;

public record AstrBotAgricultureQueryResponse(
	String username,
	String stationId,
	LocalDate startDate,
	LocalDate endDate,
	int count,
	List<PestDiseaseSummary> days
) {
}
