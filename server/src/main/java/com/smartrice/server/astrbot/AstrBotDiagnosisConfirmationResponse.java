package com.smartrice.server.astrbot;

import com.smartrice.server.realtime.DeviceControlResponse;
import java.time.Instant;
import java.util.UUID;

public record AstrBotDiagnosisConfirmationResponse(
	UUID confirmationId,
	String status,
	String stationId,
	String device,
	String username,
	Instant confirmedAt,
	Instant autoOffAt,
	DeviceControlResponse control
) {
}
