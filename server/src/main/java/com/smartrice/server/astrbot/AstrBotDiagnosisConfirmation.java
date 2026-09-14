package com.smartrice.server.astrbot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "astrbot_diagnosis_confirmation", uniqueConstraints =
	@UniqueConstraint(name = "uk_astrbot_diagnosis_confirmation", columnNames = "diagnosis_id"))
public class AstrBotDiagnosisConfirmation {

	@Id
	@Column(length = 36)
	private String id;
	@Column(name = "diagnosis_id", nullable = false)
	private long diagnosisId;
	@Column(name = "station_id", nullable = false, length = 8)
	private String stationId;
	@Column(nullable = false, length = 16)
	private String device;
	@Column(name = "expected_revision", nullable = false)
	private long expectedRevision;
	@Column(nullable = false, length = 24)
	private String status;
	@Column(name = "delivery_status", nullable = false, length = 24)
	private String deliveryStatus;
	@Column(name = "alert_message", nullable = false, length = 2000)
	private String alertMessage;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "due_at", nullable = false)
	private Instant dueAt;
	@Column(name = "sent_at")
	private Instant sentAt;
	@Column(name = "confirmed_at")
	private Instant confirmedAt;
	@Column(name = "confirmed_by", length = 64)
	private String confirmedBy;
	@Column(nullable = false)
	private int attempts;
	@Column(name = "last_error", length = 1000)
	private String lastError;

	protected AstrBotDiagnosisConfirmation() {
	}

	public AstrBotDiagnosisConfirmation(String id, long diagnosisId, String stationId, String device,
			long expectedRevision, String alertMessage, Instant createdAt, Instant dueAt) {
		this.id = id;
		this.diagnosisId = diagnosisId;
		this.stationId = stationId;
		this.device = device;
		this.expectedRevision = expectedRevision;
		this.status = "PENDING";
		this.deliveryStatus = "PENDING";
		this.alertMessage = alertMessage;
		this.createdAt = createdAt;
		this.dueAt = dueAt;
	}

	public String getId() { return id; }
	public long getDiagnosisId() { return diagnosisId; }
	public String getStationId() { return stationId; }
	public String getDevice() { return device; }
	public long getExpectedRevision() { return expectedRevision; }
	public String getStatus() { return status; }
	public void setStatus(String value) { this.status = value; }
	public String getDeliveryStatus() { return deliveryStatus; }
	public void setDeliveryStatus(String value) { this.deliveryStatus = value; }
	public String getAlertMessage() { return alertMessage; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getDueAt() { return dueAt; }
	public Instant getSentAt() { return sentAt; }
	public void setSentAt(Instant value) { this.sentAt = value; }
	public Instant getConfirmedAt() { return confirmedAt; }
	public void setConfirmedAt(Instant value) { this.confirmedAt = value; }
	public String getConfirmedBy() { return confirmedBy; }
	public void setConfirmedBy(String value) { this.confirmedBy = value; }
	public int getAttempts() { return attempts; }
	public void setAttempts(int value) { this.attempts = value; }
	public String getLastError() { return lastError; }
	public void setLastError(String value) { this.lastError = value; }
}
