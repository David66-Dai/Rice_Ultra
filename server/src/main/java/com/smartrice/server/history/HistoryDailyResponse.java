package com.smartrice.server.history;

import com.smartrice.server.pest.PestDiseaseSummary;
import java.time.LocalDate;
import java.util.List;

public record HistoryDailyResponse(
	HistoryDayData current,
	HistoryDayData previous
) {
	public record HistoryDayData(
		LocalDate date,
		String stationId,
		EnvironmentAverages environment,
		/** Only the current field day carries figures; earlier days come back with empty items. */
		PestDiseaseSummary pestDisease,
		SpectralArchive spectrum,
		String source
	) {
	}

	public record EnvironmentAverages(
		Double lightKlx,
		Double windSpeedMs,
		Double rainfallMmH,
		Double airTemperatureC,
		Double airHumidityPercent,
		Double soilNitrogenPpm,
		Double soilPhosphorusPpm,
		Double soilPotassiumPpm,
		Double soilPh,
		Double soilEcMsCm,
		Double soilTemperatureC,
		Double soilMoisturePercent
	) {
	}

	public record SpectralArchive(
		Double ndvi,
		Double ndre,
		Double gndvi,
		Double chlorophyllSpad,
		List<Double> reflectancePercent
	) {
	}
}
