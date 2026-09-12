package com.smartrice.server.astrbot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "astrbot_scheduled_stop")
public class AstrBotScheduledStop {

	@Id
	@Column(name = "request_id", length = 36)
	private String requestId;
	@Column(nullable = false, length = 64)
	private String username;
	@Column(name = "display_name", length = 64)
	private String displayName;
	@Column(name = "station_id", nullable = false, length = 8)
	private String stationId;
	@Column(nullable = false, length = 16)
	private String device;
	@Column(name = "expected_revision", nullable = false)
	private long expectedRevision;
	@Column(name = "due_at", nullable = false)
	private Instant dueAt;
	@Column(nullable = false, length = 24)
	private String status;
	@Column(nullable = false, length = 64)
	private String fingerprint;

	protected AstrBotScheduledStop() {
	}

	public AstrBotScheduledStop(String requestId, String username, String displayName, String stationId,
			String device, long expectedRevision, Instant dueAt, String status, String fingerprint) {
		this.requestId = requestId;
		this.username = username;
		this.displayName = displayName;
		this.stationId = stationId;
		this.device = device;
		this.expectedRevision = expectedRevision;
		this.dueAt = dueAt;
		this.status = status;
		this.fingerprint = fingerprint;
	}

	public String getRequestId() { return requestId; }
	public String getUsername() { return username; }
	public String getDisplayName() { return displayName; }
	public String getStationId() { return stationId; }
	public String getDevice() { return device; }
	public long getExpectedRevision() { return expectedRevision; }
	public void setExpectedRevision(long expectedRevision) { this.expectedRevision = expectedRevision; }
	public Instant getDueAt() { return dueAt; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public String getFingerprint() { return fingerprint; }
}
