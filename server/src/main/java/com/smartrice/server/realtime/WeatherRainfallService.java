package com.smartrice.server.realtime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WeatherRainfallService {

	private static final Logger log = LoggerFactory.getLogger(WeatherRainfallService.class);
	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");
	private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00");

	private final RestClient restClient;
	private final String configuredLatitude;
	private final String configuredLongitude;

	private Coordinates coordinates;
	private Double cachedRainfall;
	private Instant cacheExpiresAt = Instant.EPOCH;

	public WeatherRainfallService(RestClient.Builder builder,
			@Value("${app.realtime.weather.latitude:}") String latitude,
			@Value("${app.realtime.weather.longitude:}") String longitude) {
		this.restClient = builder.build();
		this.configuredLatitude = latitude.trim();
		this.configuredLongitude = longitude.trim();
	}

	public synchronized Double currentHourlyRainfall() {
		Instant now = Instant.now();
		if (now.isBefore(cacheExpiresAt)) {
			return cachedRainfall;
		}
		try {
			Coordinates location = resolveCoordinates();
			Map<?, ?> response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.scheme("https")
					.host("api.open-meteo.com")
					.path("/v1/forecast")
					.queryParam("latitude", location.latitude())
					.queryParam("longitude", location.longitude())
					.queryParam("hourly", "precipitation")
					.queryParam("forecast_days", 1)
					.queryParam("timezone", "Asia/Shanghai")
					.build())
				.retrieve()
				.body(Map.class);
			cachedRainfall = extractCurrentHour(response);
			cacheExpiresAt = now.plus(Duration.ofMinutes(5));
			return cachedRainfall;
		}
		catch (Exception ex) {
			log.warn("天气降雨数据获取失败，本次实时记录的降雨值留空: {}", ex.getMessage());
			cachedRainfall = null;
			cacheExpiresAt = now.plus(Duration.ofMinutes(1));
			return null;
		}
	}

	private Coordinates resolveCoordinates() {
		if (coordinates != null) {
			return coordinates;
		}
		if (!configuredLatitude.isBlank() && !configuredLongitude.isBlank()) {
			coordinates = new Coordinates(
				Double.parseDouble(configuredLatitude),
				Double.parseDouble(configuredLongitude)
			);
			return coordinates;
		}

		Map<?, ?> response = restClient.get()
			.uri("https://ipwho.is/?fields=success,latitude,longitude")
			.retrieve()
			.body(Map.class);
		if (response == null || Boolean.FALSE.equals(response.get("success"))
				|| !(response.get("latitude") instanceof Number latitude)
				|| !(response.get("longitude") instanceof Number longitude)) {
			throw new IllegalStateException("无法通过公网 IP 定位站点");
		}
		coordinates = new Coordinates(latitude.doubleValue(), longitude.doubleValue());
		log.info("未配置站点坐标，天气降雨暂按公网 IP 坐标 {}, {} 获取",
			coordinates.latitude(), coordinates.longitude());
		return coordinates;
	}

	private static Double extractCurrentHour(Map<?, ?> response) {
		if (response == null || !(response.get("hourly") instanceof Map<?, ?> hourly)
				|| !(hourly.get("time") instanceof List<?> times)
				|| !(hourly.get("precipitation") instanceof List<?> precipitation)) {
			throw new IllegalStateException("Open-Meteo 返回数据不完整");
		}
		String currentHour = LocalDateTime.now(FIELD_ZONE).format(HOUR_FORMAT);
		int index = times.indexOf(currentHour);
		if (index < 0 || index >= precipitation.size()
				|| !(precipitation.get(index) instanceof Number value)) {
			throw new IllegalStateException("Open-Meteo 未返回当前小时降雨量");
		}
		return value.doubleValue();
	}

	private record Coordinates(double latitude, double longitude) {
	}
}
