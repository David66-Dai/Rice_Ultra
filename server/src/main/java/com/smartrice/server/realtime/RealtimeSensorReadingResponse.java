package com.smartrice.server.realtime;

import java.time.Instant;

public record RealtimeSensorReadingResponse(
	Instant sampledAt,
	Double lightKlx,
	Double windSpeedMs,
	Double rainfallMmH,
	Double airTemperatureC,
	Double airHumidityPercent,
	Double soilNitrogenMgKg,
	Double soilPhosphorusMgKg,
	Double soilPotassiumMgKg,
	Double soilPh,
	Double soilEcMsCm
) {
}
