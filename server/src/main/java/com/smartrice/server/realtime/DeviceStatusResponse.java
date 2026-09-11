package com.smartrice.server.realtime;

import java.time.Instant;

public record DeviceStatusResponse(
	String stationId,
	boolean pump,
	boolean lamp,
	Instant updatedAt
) {
}
