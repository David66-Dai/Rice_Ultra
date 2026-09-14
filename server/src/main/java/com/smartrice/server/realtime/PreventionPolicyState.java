package com.smartrice.server.realtime;

import java.time.Instant;

public record PreventionPolicyState(
	boolean requireAstrBotConfirmation,
	long revision,
	Instant updatedAt,
	String updatedBy,
	boolean spraySafetyEnabled,
	double maxSprayWindSpeedMs,
	long sensorMaxAgeSeconds,
	long leafEvidenceMaxAgeSeconds
) {
}
