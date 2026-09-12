package com.smartrice.server.diagnosis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
	name = "inspection_diagnosis",
	indexes = @Index(name = "idx_diagnosis_station_task_time", columnList = "station_id,task,created_at")
)
public class InspectionDiagnosis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "station_id", nullable = false, length = 8)
	private String stationId;

	@Column(nullable = false, length = 16)
	private String task;

	@Column(length = 255)
	private String filename;

	@Column(length = 128)
	private String label;

	@Column(name = "label_zh", length = 128)
	private String labelZh;

	private Double confidence;

	@Column(name = "detection_count", nullable = false)
	private int detectionCount;

	@Column(name = "alert_level", nullable = false, length = 16)
	private String alertLevel;

	@Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
	private String resultJson;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected InspectionDiagnosis() {
	}

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public Long getId() {
		return id;
	}

	public String getStationId() {
		return stationId;
	}

	public void setStationId(String stationId) {
		this.stationId = stationId;
	}

	public String getTask() {
		return task;
	}

	public void setTask(String task) {
		this.task = task;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public String getLabelZh() {
		return labelZh;
	}

	public void setLabelZh(String labelZh) {
		this.labelZh = labelZh;
	}

	public Double getConfidence() {
		return confidence;
	}

	public void setConfidence(Double confidence) {
		this.confidence = confidence;
	}

	public int getDetectionCount() {
		return detectionCount;
	}

	public void setDetectionCount(int detectionCount) {
		this.detectionCount = detectionCount;
	}

	public String getAlertLevel() {
		return alertLevel;
	}

	public void setAlertLevel(String alertLevel) {
		this.alertLevel = alertLevel;
	}

	public String getResultJson() {
		return resultJson;
	}

	public void setResultJson(String resultJson) {
		this.resultJson = resultJson;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
