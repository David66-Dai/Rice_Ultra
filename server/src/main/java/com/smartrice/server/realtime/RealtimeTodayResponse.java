package com.smartrice.server.realtime;

import java.time.LocalDate;
import java.util.List;

public record RealtimeTodayResponse(
	String stationId,
	LocalDate date,
	List<RealtimeSensorReadingResponse> readings
) {
}
