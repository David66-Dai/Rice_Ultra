package com.smartrice.server.diagnosis;

import java.time.Instant;
import java.util.Map;

public record DiagnosisResponse(
	long id,
	String stationId,
	String task,
	String filename,
	String label,
	String labelZh,
	Double confidence,
	int detectionCount,
	String alertLevel,
	String stationAlertLevel,
	Instant createdAt,
	Map<String, Object> result,
	String activatedDevice,
	String deviceError,
	boolean confirmationRequired,
	String pendingConfirmationId,
	String alertDeliveryStatus
) {
}
