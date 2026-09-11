package com.smartrice.server.realtime;

public record DeviceControlRequest(
	String stationId,
	String device,
	boolean enabled
) {
}
