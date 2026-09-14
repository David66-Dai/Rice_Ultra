package com.smartrice.server.realtime;

import java.time.Instant;

public record WindReadingEvent(String stationId, Double windSpeedMs, Instant sampledAt) {
}
