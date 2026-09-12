package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SerialSensorFrameParserTests {
	// Actual station-1 protocol preserved by the legacy Go collector's regression fixture.
	static final String[] CYCLE = {
		"当前站点1光照值为：225LUX", "当前站点1温度为：24摄氏度",
		"当前站点1湿度为：百分之44", "当前站点1PH值为：6.2",
		"当前站点1电导率为1.2ds/m", "当前站点1风速为：0米/秒",
		"当前站点1土壤温度为：28摄氏度", "当前站点1土壤湿度为：百分之0",
		"当前站点1氮肥浓度为：103PPM", "当前站点1磷肥浓度为：18PPM"
	};
	@Test void actualTenMetricCyclePublishesWithoutPotassiumAndKeepsSoilReadings() {
		var parser = new SerialSensorFrameParser("S01");
		Instant now = Instant.now();
		feed(parser, now);
		var reading = parser.accept(CYCLE[0], now.plusSeconds(11));
		assertThat(reading).isNotNull();
		assertThat(reading.lightKlx).isEqualTo(.225);
		assertThat(reading.soilTemperatureC).isEqualTo(28);
		assertThat(reading.soilMoisturePercent).isZero();
		assertThat(reading.soilEcMsCm).isEqualTo(1.2);
		assertThat(reading.soilPotassiumMgKg).isNull();
		assertThat(reading.startedAt).isEqualTo(now);
		assertThat(reading.sampledAt).isEqualTo(now.plusSeconds(9));
	}
	@Test void optionalPotassiumIsIncludedAndDoesNotCarryToNextRound() {
		var parser = new SerialSensorFrameParser("S01");
		Instant now = Instant.now();
		feed(parser, now);
		parser.accept("当前站点1钾肥浓度为：42PPM", now.plusSeconds(10));
		assertThat(parser.accept(CYCLE[0], now.plusSeconds(11)).soilPotassiumMgKg).isEqualTo(42);
		feed(parser, now.plusSeconds(12));
		assertThat(parser.accept(CYCLE[0], now.plusSeconds(23)).soilPotassiumMgKg).isNull();
	}
	@Test void incompleteDuplicateCrossStationAndExpiredRoundsAreDiscarded() {
		for (String bad : new String[]{CYCLE[2], CYCLE[2].replace("站点1", "站点2"), "invalid"}) {
			var parser = new SerialSensorFrameParser("S01");
			Instant now = Instant.now();
			feed(parser, now);
			parser.accept(bad, now.plusSeconds(10));
			assertThat(parser.accept(CYCLE[0], now.plusSeconds(11))).isNull();
		}
		var parser = new SerialSensorFrameParser("S01");
		Instant now = Instant.now();
		feed(parser, now);
		assertThat(parser.accept(CYCLE[0], now.plusSeconds(60))).isNull();
	}
	@Test void observedFirmwareSeparatorsAreAccepted() {
		var parser = new SerialSensorFrameParser("S01");
		Instant now = Instant.now();
		for (int i = 0; i < CYCLE.length; i++) {
			parser.accept(CYCLE[i].replace("PH值为：", "PH值为：\u0000").replace("电导率为", "电导率为�"), now.plusSeconds(i));
		}
		assertThat(parser.accept(CYCLE[0], now.plusSeconds(11))).isNotNull();
	}
	private static void feed(SerialSensorFrameParser parser, Instant now) {
		for (int i = 0; i < CYCLE.length; i++) assertThat(parser.accept(CYCLE[i], now.plusSeconds(i))).isNull();
	}
}
