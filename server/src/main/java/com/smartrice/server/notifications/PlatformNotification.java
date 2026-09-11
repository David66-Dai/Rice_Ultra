package com.smartrice.server.notifications;

import java.time.Instant;

public record PlatformNotification(long id, String type, String message, Instant createdAt,
		String stationId, String actorUsername, String actorDisplayName, String device, Boolean enabled) {
}
