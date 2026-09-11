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
		SpectralArchive spectrum
	) {
	}

	public record EnvironmentAverages(
		double lightKlx,
		double windSpeedMs,
		double rainfallMmH,
		double airTemperatureC,
		double airHumidityPercent,
		double soilNitrogenMgKg,
		double soilPhosphorusMgKg,
		double soilPotassiumMgKg,
		double soilPh,
		double soilEcMsCm
	) {
	}

	public record PestDiseaseArchive(
		int diseaseCount,
		double pestDensityPer100Plants,
		double affectedAreaPercent,
		double riskIndex,
		double recognitionConfidencePercent
	) {
	}

	public record SpectralArchive(
		double ndvi,
		double ndre,
		double gndvi,
		double chlorophyllSpad,
		List<Double> reflectancePercent
	) {
	}
}
