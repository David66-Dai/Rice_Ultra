package com.smartrice.server.astrbot;

import com.smartrice.server.realtime.DeviceControlResponse;
import java.time.Instant;
import java.util.UUID;

public record AstrBotControlResponse(
	UUID requestId,
	String username,
	boolean testMode,
	boolean secondaryConfirmationSkipped,
	Instant autoOffAt,
	DeviceControlResponse control
) {
}
