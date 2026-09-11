package com.smartrice.server.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;

@Entity
@Table(
	name = "historical_daily_data",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_history_date_station",
		columnNames = {"record_date", "station_id"}
	),
	indexes = {
		@Index(name = "idx_history_station_date", columnList = "station_id,record_date"),
		@Index(name = "idx_history_record_date", columnList = "record_date")
	}
)
public class HistoricalDailyData {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "record_date", nullable = false)
	LocalDate recordDate;

	@Column(name = "station_id", nullable = false, length = 8)
	String stationId;

	@Column(name = "avg_light_klx", nullable = false)
	double avgLightKlx;

	@Column(name = "avg_wind_speed_m_s", nullable = false)
	double avgWindSpeedMs;

	@Column(name = "avg_rainfall_mm_h", nullable = false)
	double avgRainfallMmH;

	@Column(name = "avg_air_temperature_c", nullable = false)
	double avgAirTemperatureC;

	@Column(name = "avg_air_humidity_percent", nullable = false)
	double avgAirHumidityPercent;

	@Column(name = "avg_soil_nitrogen_mg_kg", nullable = false)
	double avgSoilNitrogenMgKg;

	@Column(name = "avg_soil_phosphorus_mg_kg", nullable = false)
	double avgSoilPhosphorusMgKg;

	@Column(name = "avg_soil_potassium_mg_kg", nullable = false)
	double avgSoilPotassiumMgKg;

	@Column(name = "avg_soil_ph", nullable = false)
	double avgSoilPh;

	@Column(name = "avg_soil_ec_ms_cm", nullable = false)
	double avgSoilEcMsCm;

	@Column(name = "disease_count", nullable = false)
	int diseaseCount;

	@Column(name = "pest_density_per_100_plants", nullable = false)
	double pestDensityPer100Plants;

	@Column(name = "affected_area_percent", nullable = false)
	double affectedAreaPercent;

	@Column(name = "pest_disease_risk_index", nullable = false)
	double pestDiseaseRiskIndex;

	@Column(name = "recognition_confidence_percent", nullable = false)
	double recognitionConfidencePercent;

	@Column(nullable = false)
	double ndvi;

	@Column(nullable = false)
	double ndre;

	@Column(nullable = false)
	double gndvi;

	@Column(name = "chlorophyll_spad", nullable = false)
	double chlorophyllSpad;

	@Column(name = "reflectance_450nm_percent", nullable = false)
	double reflectance450nmPercent;

	@Column(name = "reflectance_550nm_percent", nullable = false)
	double reflectance550nmPercent;

	@Column(name = "reflectance_650nm_percent", nullable = false)
	double reflectance650nmPercent;

	@Column(name = "reflectance_720nm_percent", nullable = false)
	double reflectance720nmPercent;

	@Column(name = "reflectance_800nm_percent", nullable = false)
	double reflectance800nmPercent;

	@Column(name = "reflectance_900nm_percent", nullable = false)
	double reflectance900nmPercent;

	protected HistoricalDailyData() {
	}
}
