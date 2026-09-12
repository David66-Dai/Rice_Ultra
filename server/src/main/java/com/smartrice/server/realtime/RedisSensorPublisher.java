package com.smartrice.server.realtime;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.redis.enabled", havingValue = "true")
public class RedisSensorPublisher {
	private static final Logger log = LoggerFactory.getLogger(RedisSensorPublisher.class);
	// Same thirteen-field JSON, timestamp ordering and seven-day history as the legacy collector.
	static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
		local now = redis.call('TIME')
		local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
		local ttl = tonumber(ARGV[3]) - now_ms
		local history_ttl = tonumber(ARGV[4]) - now_ms
		if history_ttl <= 0 then return 1 end
		local previous = redis.call('GET', KEYS[2])
		local update = ttl > 0
		if previous then
		  local ok, value = pcall(cjson.decode, previous)
		  if not ok or type(value) ~= 'table' then return 0 end
		  if type(value.date) == 'string' then
		    if value.date >= ARGV[2] then update = false end
		  elseif type(value.timestamp_us) == 'number' then
		    if value.timestamp_us >= tonumber(ARGV[5]) then update = false end
		  else return 0 end
		end
		local history = redis.call('GET', KEYS[1])
		if history and history ~= ARGV[1] then return 0 end
		if not history then redis.call('SET', KEYS[1], ARGV[1], 'PX', history_ttl) end
		if update then
		  redis.call('SET', KEYS[2], ARGV[1], 'PX', ttl)
		end
		return 1
		""", Long.class);
	private static final DateTimeFormatter ORDER = new java.time.format.DateTimeFormatterBuilder()
		.appendInstant(6).toFormatter();
	private final RedisPendingSampleRepository pending;
	private final StringRedisTemplate redis;
	private final long freshnessMs;

	public RedisSensorPublisher(RedisPendingSampleRepository pending, StringRedisTemplate redis,
			@Value("${app.realtime.redis.freshness-seconds:30}") long freshnessSeconds) {
		if (freshnessSeconds < 1 || freshnessSeconds > 3600) throw new IllegalArgumentException("Invalid Redis freshness-seconds");
		this.pending = pending;
		this.redis = redis;
		this.freshnessMs = freshnessSeconds * 1000;
	}

	@Scheduled(fixedDelayString = "${app.realtime.redis.retry-delay-ms:1000}")
	public void publishPending() {
		try {
			for (RedisPendingSample sample : pending.findTop100ByOrderBySampledAtAscIdAsc()) {
				publish(sample);
				pending.deleteById(sample.id);
			}
		} catch (RuntimeException ex) {
			// Exception messages may contain connection credentials. Retain the queue for retry.
			log.warn("Redis 实时同步失败 ({})，待同步记录已保留，下轮重试", ex.getClass().getSimpleName());
		}
	}

	void publish(RedisPendingSample sample) {
		String base = "farm:device:" + sample.deviceId + ":environment:";
		// Translate remaining lifetime into Redis time, conservatively subtracting the
		// TIME round trip so different host clocks cannot revive a stale reading.
		long remoteNow = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
		long localNow = Instant.now().toEpochMilli();
		long latestDeadline = remoteNow + sample.startedAt.toEpochMilli() + freshnessMs - localNow;
		long historyDeadline = remoteNow + sample.sampledAt.toEpochMilli() + java.time.Duration.ofDays(7).toMillis() - localNow;
		Long result = redis.execute(WRITE,
			List.of(historyKey(sample), base + "latest"),
			sample.payload, ORDER.format(sample.sampledAt), Long.toString(latestDeadline),
			Long.toString(historyDeadline), Long.toString(micros(sample.sampledAt)));
		if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("Redis did not acknowledge sample");
	}

	static String historyKey(RedisPendingSample sample) {
		return "farm:device:" + sample.deviceId + ":environment:history:" + micros(sample.sampledAt) + ":" + sample.id;
	}

	private static long micros(Instant time) { return time.getEpochSecond() * 1_000_000 + time.getNano() / 1000; }
}
