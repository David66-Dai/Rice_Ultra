package com.smartrice.server.diagnosis;

import java.time.Instant;
import java.util.List;

public record StationAlertListResponse(List<StationAlertStatus> stations) {

	public record StationAlertStatus(
		String stationId,
		String alertLevel,
		String leafAlertLevel,
		String leafLabel,
		String leafLabelZh,
		Double leafConfidence,
		String pestAlertLevel,
		Integer pestCount,
		String pestLabel,
		Instant updatedAt
	) {
	}
}
