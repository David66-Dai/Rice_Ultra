package com.smartrice.server.ai;

import java.time.Instant;
import java.time.LocalDate;

public record AiAnalysisJob(String id, String stationId, LocalDate date, int windowDays,
	String status, Instant createdAt, Instant completedAt, String error, AiAnalysisResult result,
	Instant generatedAt, Instant expiresAt, boolean expired, String archiveId, String archivePath) {
	public AiAnalysisJob(String id, String stationId, LocalDate date, int windowDays,
		String status, Instant createdAt, Instant completedAt, String error, AiAnalysisResult result) {
		this(id, stationId, date, windowDays, status, createdAt, completedAt, error, result, null, null, false, null, null);
	}
}
