package com.smartrice.server.astrbot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AstrBotAlertQueryResponse(
	String username,
	String stationId,
	LocalDate startDate,
	LocalDate endDate,
	int count,
	boolean truncated,
	List<AlertItem> alerts
) {
	public record AlertItem(
		long id,
		String stationId,
		String task,
		String alertLevel,
		String label,
		String labelZh,
		Double confidence,
		int detectionCount,
		Instant createdAt,
		String deliveryStatus
	) {
	}
}
