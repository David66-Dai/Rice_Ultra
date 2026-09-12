package com.smartrice.server.astrbot;

import com.smartrice.server.realtime.DeviceState;
import java.util.List;

/** Least-privilege synchronization response: no notification history or unrelated platform data. */
public record AstrBotDeviceSnapshot(
	String username,
	String displayName,
	boolean canControl,
	boolean available,
	List<DeviceState> devices,
	boolean testControlAllowed,
	long confirmationTtlSeconds,
	int maxDurationSeconds
) {
}
