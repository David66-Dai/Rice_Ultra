package com.smartrice.server.history;

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
		PestDiseaseArchive pestDisease,
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

	public record PestDiseaseArchive(
		Integer diseaseCount,
		Double pestDensityPer100Plants,
		Double affectedAreaPercent,
		Double riskIndex,
		Double recognitionConfidencePercent
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
