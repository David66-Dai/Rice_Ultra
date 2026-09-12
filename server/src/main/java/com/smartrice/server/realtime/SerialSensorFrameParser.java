package com.smartrice.server.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** Station protocol: ten ordered metrics and optional potassium; next light closes a round. */
final class SerialSensorFrameParser {
	private static final Pattern PREFIX = Pattern.compile("^当前站点\\s*(\\d{1,2})\\s*(.*)$");
	private static final String NUMBER = "([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))";
	private static final Pattern[] METRICS = {
		metric("光照值", "", "LUX"), metric("温度", "", "摄氏度"),
		metric("湿度", "百分之", ""), metric("PH值", "", ""),
		Pattern.compile("^电导率为\\s*[:：�]?\\s*" + NUMBER + "\\s*dS/m$", Pattern.CASE_INSENSITIVE),
		metric("风速", "", "米/秒"), metric("土壤温度", "", "摄氏度"),
		metric("土壤湿度", "百分之", ""), metric("氮肥浓度", "", "PPM"),
		metric("磷肥浓度", "", "PPM"), metric("钾肥浓度", "", "PPM")
	};
	private final String station;
	private int count;
	private double[] values = new double[11];
	private Instant startedAt;
	private Instant sampledAt;

	SerialSensorFrameParser(String station) { this.station = station; }

	RealtimeSensorReading accept(String line, Instant now) {
		if (startedAt != null && (now.isBefore(startedAt) || Duration.between(startedAt, now).toSeconds() >= 60)) clear();
		line = line.replace("\u0000", "").trim();
		if (line.isEmpty()) return null;
		var prefix = PREFIX.matcher(line);
		if (!prefix.matches() || !station.equals("S%02d".formatted(Integer.parseInt(prefix.group(1))))) {
			clear();
			return null;
		}
		int index = -1;
		double value = 0;
		for (int i = 0; i < METRICS.length; i++) {
			var matcher = METRICS[i].matcher(prefix.group(2));
			if (matcher.matches()) {
				index = i;
				value = Double.parseDouble(matcher.group(1));
				break;
			}
		}
		if (index < 0 || !Double.isFinite(value)) { clear(); return null; }
		if (index == 0) {
			RealtimeSensorReading completed = count >= 10 ? finish() : null;
			clear();
			startedAt = now;
			sampledAt = now;
			values[0] = value;
			count = 1;
			return completed;
		}
		if (count == 0) return null;
		if (index != count) { clear(); return null; }
		values[index] = value;
		count++;
		sampledAt = now;
		return null;
	}

	private RealtimeSensorReading finish() {
		var reading = new RealtimeSensorReading();
		reading.stationId = station;
		reading.startedAt = startedAt;
		reading.sampledAt = sampledAt;
		reading.lightKlx = values[0] / 1000;
		reading.airTemperatureC = values[1];
		reading.airHumidityPercent = values[2];
		reading.soilPh = values[3];
		reading.soilEcMsCm = values[4];
		reading.windSpeedMs = values[5];
		reading.soilTemperatureC = values[6];
		reading.soilMoisturePercent = values[7];
		reading.soilNitrogenMgKg = values[8];
		reading.soilPhosphorusMgKg = values[9];
		reading.soilPotassiumMgKg = count == 11 ? values[10] : null;
		return reading;
	}

	private void clear() { count = 0; startedAt = null; sampledAt = null; values = new double[11]; }
	private static Pattern metric(String name, String before, String unit) {
		return Pattern.compile("^" + name + "为\\s*[:：]\\s*" + before + "\\s*" + NUMBER + "\\s*" + unit + "$", Pattern.CASE_INSENSITIVE);
	}
}
