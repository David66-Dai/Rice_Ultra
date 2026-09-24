package com.smartrice.server.realtime;

import java.time.Instant;

/** 一条采样落库后发布，用于立刻唤醒等待该站点实时数据的页面。 */
public record RealtimeReadingEvent(String stationId, Instant sampledAt) {
}
