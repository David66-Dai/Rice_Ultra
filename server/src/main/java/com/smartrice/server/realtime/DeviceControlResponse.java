package com.smartrice.server.realtime;

import java.time.Instant;

public record DeviceControlResponse(
	String stationId,
	String device,
	boolean enabled,
	String command,
	Instant sentAt
) {
}
