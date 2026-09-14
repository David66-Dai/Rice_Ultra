package com.smartrice.server.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "platform_notification")
public class NotificationEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 32)
	private String type;
	@Column(nullable = false, length = 2000)
	private String message;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "station_id", length = 8)
	private String stationId;
	@Column(name = "actor_username", length = 64)
	private String actorUsername;
	@Column(name = "actor_display_name", length = 64)
	private String actorDisplayName;
	@Column(length = 16)
	private String device;
	private Boolean enabled;
	@Column(name = "source_id")
	private Long sourceId;

	protected NotificationEvent() {
	}

	public NotificationEvent(String type, String message, Instant createdAt, String stationId,
			String actorUsername, String actorDisplayName, String device, Boolean enabled) {
		this(type, message, createdAt, stationId, actorUsername, actorDisplayName, device, enabled, null);
	}

	public NotificationEvent(String type, String message, Instant createdAt, String stationId,
			String actorUsername, String actorDisplayName, String device, Boolean enabled, Long sourceId) {
		this.type = type;
		this.message = message;
		this.createdAt = createdAt;
		this.stationId = stationId;
		this.actorUsername = actorUsername;
		this.actorDisplayName = actorDisplayName;
		this.device = device;
		this.enabled = enabled;
		this.sourceId = sourceId;
	}

	public Long getSourceId() { return sourceId; }

	public PlatformNotification toResponse() {
		return new PlatformNotification(id, type, message, createdAt, stationId,
			actorUsername, actorDisplayName, device, enabled);
	}
}
