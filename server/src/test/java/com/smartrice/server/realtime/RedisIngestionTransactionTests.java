package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "app.realtime.redis.enabled=true")
class RedisIngestionTransactionTests {
	@Autowired RealtimeSensorReadingRepository readings;
	@Autowired RedisPendingSampleRepository pending;
	@Autowired RealtimeSensorIngestionService service;
	@MockitoBean RedisSensorPublisher publisher;

	@Test void readingAndDeliveryQueueCommitAndRollbackTogether() {
		var invalid = RedisSensorTests.reading();
		invalid.stationId = "BAD";
		assertThatThrownBy(() -> service.save(invalid)).isInstanceOf(NumberFormatException.class);
		assertThat(readings.count()).isZero();
		assertThat(pending.count()).isZero();
		service.save(RedisSensorTests.reading());
		assertThat(readings.count()).isEqualTo(1);
		assertThat(pending.count()).isEqualTo(1);
		pending.deleteAll();
		readings.deleteAll();
	}
}
