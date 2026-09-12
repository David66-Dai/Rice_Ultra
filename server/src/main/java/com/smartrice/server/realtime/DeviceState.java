package com.smartrice.server.realtime;

import java.time.Instant;

/** Last successfully written command; null means unknown, not a physical device acknowledgement. */
public record DeviceState(String stationId, String device, Boolean enabled, long revision,
		Instant updatedAt, String updatedBy) {
}
