package com.smartrice.server.realtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "prevention_policy")
public class PreventionPolicy {

	@Id
	private Integer id;
	@Column(name = "require_astrbot_confirmation", nullable = false)
	private boolean requireAstrBotConfirmation;
	@Column(nullable = false)
	private long revision;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
	@Column(name = "updated_by", length = 64)
	private String updatedBy;

	protected PreventionPolicy() {
	}

	public PreventionPolicy(boolean required) {
		this.id = 1;
		this.requireAstrBotConfirmation = required;
		this.updatedAt = Instant.now();
		this.revision = 0;
	}

	public boolean isRequireAstrBotConfirmation() { return requireAstrBotConfirmation; }
	public void setRequireAstrBotConfirmation(boolean value) { this.requireAstrBotConfirmation = value; }
	public long getRevision() { return revision; }
	public void setRevision(long value) { this.revision = value; }
	public Instant getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(Instant value) { this.updatedAt = value; }
	public String getUpdatedBy() { return updatedBy; }
	public void setUpdatedBy(String value) { this.updatedBy = value; }
}
