package com.smartrice.server.realtime;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Durable delivery queue, committed in the same transaction as the MySQL reading. */
@Entity
@Table(name = "redis_pending_sample", indexes = @Index(name = "idx_redis_pending_time", columnList = "sampled_at,id"))
public class RedisPendingSample {
	@Id
	@Column(length = 36)
	String id;
	@Column(nullable = false, length = 128)
	String deviceId;
	@Column(name = "sampled_at", nullable = false)
	Instant sampledAt;
	@Column(name = "started_at", nullable = false)
	Instant startedAt;
	@Column(nullable = false, length = 4096)
	String payload;

	protected RedisPendingSample() {}

	RedisPendingSample(String deviceId, Instant sampledAt, String payload) {
		this.id = UUID.randomUUID().toString();
		this.deviceId = deviceId;
		this.sampledAt = sampledAt;
		this.startedAt = sampledAt;
		this.payload = payload;
	}
}
