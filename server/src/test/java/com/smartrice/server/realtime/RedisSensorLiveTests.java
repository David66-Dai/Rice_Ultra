package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.config.RiceConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Duration;

/** Explicit opt-in; writes only a random test device namespace, removed in finally. No serial access. */
@EnabledIfEnvironmentVariable(named = "RICE_REDIS_LIVE_TEST", matches = "true")
class RedisSensorLiveTests {
	@Test void realRedisRoundTripOrderingExpirationAndRetry() throws Exception {
		var env = RiceConfiguration.loadEnvironment();
		var config = new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"),
			env.getProperty("spring.data.redis.port", Integer.class, 6379));
		config.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
		String username = env.getProperty("spring.data.redis.username", "");
		String password = env.getProperty("spring.data.redis.password", "");
		if (!username.isBlank()) config.setUsername(username);
		if (!password.isEmpty()) config.setPassword(password);
		var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2));
		if (env.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false)) client.useSsl();
		var factory = new LettuceConnectionFactory(config, client.build());
		factory.afterPropertiesSet();
		factory.start();
		var redis = new StringRedisTemplate(factory);
		String device = "rice_ultra_test_" + UUID.randomUUID().toString().replace("-", "");
		String base = "farm:device:" + device + ":environment:";
		var keys = new ArrayList<String>();
		keys.add(base + "latest");
		keys.add(base + "latest:order");
		try {
			var json = new ObjectMapper();
			var ingestion = new RealtimeSensorIngestionService(mock(RealtimeSensorReadingRepository.class),
				mock(RedisPendingSampleRepository.class), json, true, device,
				mock(ApplicationEventPublisher.class));
			var publisher = new RedisSensorPublisher(mock(RedisPendingSampleRepository.class), redis, 30);
			var reading = RedisSensorTests.reading();
			reading.sampledAt = Instant.now();
			var sample = new RedisPendingSample(device, reading.sampledAt, ingestion.payload(reading));
			keys.add(RedisSensorPublisher.historyKey(sample));
			publisher.publish(sample);
			assertThat(redis.opsForValue().get(base + "latest")).isEqualTo(sample.payload);
			assertThat(redis.opsForValue().get(RedisSensorPublisher.historyKey(sample))).isEqualTo(sample.payload);
			assertThat(redis.getExpire(RedisSensorPublisher.historyKey(sample))).isBetween(604790L, 604800L);
			long ttl = redis.getExpire(base + "latest", TimeUnit.MILLISECONDS);
			assertThat(ttl).isBetween(1L, 30000L);
			publisher.publish(sample);
			assertThat(redis.getExpire(base + "latest", TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
			reading.sampledAt = reading.sampledAt.minusSeconds(2);
			var older = new RedisPendingSample(device, reading.sampledAt, ingestion.payload(reading));
			keys.add(RedisSensorPublisher.historyKey(older));
			publisher.publish(older);
			assertThat(redis.opsForValue().get(base + "latest")).isEqualTo(sample.payload);
			redis.delete(base + "latest");
			redis.delete(base + "latest:order");
			reading.sampledAt = reading.sampledAt.minusSeconds(60);
			var expired = new RedisPendingSample(device, reading.sampledAt, ingestion.payload(reading));
			keys.add(RedisSensorPublisher.historyKey(expired));
			publisher.publish(expired);
			assertThat(redis.hasKey(base + "latest")).isFalse();
			assertThat(redis.opsForValue().get(RedisSensorPublisher.historyKey(expired))).isEqualTo(expired.payload);
			System.out.println("PASS: live Redis write/read, 13-field JSON, history retention, latest TTL, idempotent retry, ordering and expired backlog.");
		} finally {
			try {
				redis.delete(keys);
				assertThat(redis.countExistingKeys(keys)).isZero();
				System.out.println("PASS: all temporary test keys removed.");
			} finally {
				factory.destroy();
			}
		}
	}
}
