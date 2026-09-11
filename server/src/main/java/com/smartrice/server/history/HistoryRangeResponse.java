package com.smartrice.server.history;

import java.time.LocalDate;

public record HistoryRangeResponse(
	String stationId,
	LocalDate startDate,
	LocalDate endDate,
	long recordCount
) {
}
