package com.smartrice.server.realtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
	name = "realtime_sensor_reading",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_realtime_station_sampled",
		columnNames = {"station_id", "sampled_at"}
	),
	indexes = @Index(name = "idx_realtime_station_time", columnList = "station_id,sampled_at")
)
public class RealtimeSensorReading {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "station_id", nullable = false, length = 8)
	String stationId;

	@Column(name = "sampled_at", nullable = false)
	Instant sampledAt;

	@Column(name = "light_klx")
	Double lightKlx;

	@Column(name = "wind_speed_m_s")
	Double windSpeedMs;

	@Column(name = "rainfall_mm_h")
	Double rainfallMmH;

	@Column(name = "air_temperature_c")
	Double airTemperatureC;

	@Column(name = "air_humidity_percent")
	Double airHumidityPercent;

	@Column(name = "soil_nitrogen_mg_kg")
	Double soilNitrogenMgKg;

	@Column(name = "soil_phosphorus_mg_kg")
	Double soilPhosphorusMgKg;

	@Column(name = "soil_potassium_mg_kg")
	Double soilPotassiumMgKg;

	@Column(name = "soil_ph")
	Double soilPh;

	@Column(name = "soil_ec_ms_cm")
	Double soilEcMsCm;

	protected RealtimeSensorReading() {
	}
}
