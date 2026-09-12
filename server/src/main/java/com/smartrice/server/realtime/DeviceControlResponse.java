package com.smartrice.server.realtime;

import java.time.Instant;

public record DeviceControlResponse(
	String stationId,
	String device,
	boolean enabled,
	String command,
	Instant sentAt,
	DeviceState state
) {

	/** Compatibility constructor for legacy low-level callers; synchronized state is optional there. */
	public DeviceControlResponse(String stationId, String device, boolean enabled,
			String command, Instant sentAt) {
		this(stationId, device, enabled, command, sentAt, null);
	}
}
