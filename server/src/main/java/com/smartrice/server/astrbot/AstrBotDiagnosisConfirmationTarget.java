package com.smartrice.server.astrbot;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Delivery state of one prevention confirmation in one AstrBot session (UMO).
 *
 * <p>Alerts go to every configured session — WeChat and QQ at the same time when both are
 * online. Each session keeps its own status, attempt count, error and delivery time, so one
 * platform failing never stops another from being alerted, and a failing session is retried
 * without re-sending to a session that already received the alert.</p>
 *
 * <p>Only a session whose row reached {@code SENT} may confirm the alert: a user can never
 * release a device from a chat that did not actually receive the confirmation request.</p>
 */
@Entity
@Table(name = "astrbot_diagnosis_confirmation_target",
	indexes = @Index(name = "idx_astrbot_confirmation_target_status",
		columnList = "confirmation_id,delivery_status"))
public class AstrBotDiagnosisConfirmationTarget {

	@EmbeddedId
	private Key key;
	@Column(name = "delivery_status", nullable = false, length = 24)
	private String deliveryStatus;
	@Column(nullable = false)
	private int attempts;
	@Column(name = "sent_at")
	private Instant sentAt;
	@Column(name = "last_error", length = 1000)
	private String lastError;

	protected AstrBotDiagnosisConfirmationTarget() {
	}

	public AstrBotDiagnosisConfirmationTarget(String confirmationId, String umo) {
		this.key = new Key(confirmationId, umo);
		this.deliveryStatus = "PENDING";
	}

	public Key getKey() { return key; }
	public String getConfirmationId() { return key == null ? null : key.getConfirmationId(); }
	public String getUmo() { return key == null ? null : key.getUmo(); }
	public String getDeliveryStatus() { return deliveryStatus; }
	public void setDeliveryStatus(String value) { this.deliveryStatus = value; }
	public int getAttempts() { return attempts; }
	public void setAttempts(int value) { this.attempts = value; }
	public Instant getSentAt() { return sentAt; }
	public void setSentAt(Instant value) { this.sentAt = value; }
	public String getLastError() { return lastError; }
	public void setLastError(String value) { this.lastError = value; }

	public boolean isDelivered() { return "SENT".equals(deliveryStatus); }

	@Embeddable
	public static class Key implements Serializable {

		@Column(name = "confirmation_id", nullable = false, length = 36)
		private String confirmationId;
		@Column(name = "umo", nullable = false, length = 255)
		private String umo;

		protected Key() {
		}

		public Key(String confirmationId, String umo) {
			this.confirmationId = confirmationId;
			this.umo = umo;
		}

		public String getConfirmationId() { return confirmationId; }
		public String getUmo() { return umo; }

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			return other instanceof Key key
				&& Objects.equals(confirmationId, key.confirmationId) && Objects.equals(umo, key.umo);
		}

		@Override
		public int hashCode() {
			return Objects.hash(confirmationId, umo);
		}
	}
}
