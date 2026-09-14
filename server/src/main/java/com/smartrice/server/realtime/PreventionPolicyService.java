package com.smartrice.server.realtime;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PreventionPolicyService {

	private final PreventionPolicyRepository repository;
	private final PreventionControlProperties properties;

	public PreventionPolicyService(PreventionPolicyRepository repository, PreventionControlProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	public synchronized PreventionPolicyState current() {
		return state(entity());
	}

	public synchronized PreventionPolicyState update(boolean required, long expectedRevision, String username) {
		PreventionPolicy row = entity();
		if (row.getRevision() != expectedRevision) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "防治确认开关已被其他用户更新，请同步后重试");
		}
		if (row.isRequireAstrBotConfirmation() == required) return state(row);
		row.setRequireAstrBotConfirmation(required);
		row.setRevision(row.getRevision() + 1);
		row.setUpdatedAt(Instant.now());
		row.setUpdatedBy(username);
		return state(repository.saveAndFlush(row));
	}

	private PreventionPolicy entity() {
		return repository.findById(1).orElseGet(() -> repository.saveAndFlush(
			new PreventionPolicy(properties.isRequireAstrBotConfirmationDefault())));
	}

	private PreventionPolicyState state(PreventionPolicy row) {
		return new PreventionPolicyState(row.isRequireAstrBotConfirmation(), row.getRevision(), row.getUpdatedAt(),
			row.getUpdatedBy(), properties.isSpraySafetyEnabled(), threshold(),
			seconds(properties.getSensorMaxAge(), 120), seconds(properties.getLeafEvidenceMaxAge(), 86400));
	}

	private double threshold() {
		double value = properties.getMaxSprayWindSpeedMs();
		return Double.isFinite(value) && value >= 0 ? value : 0;
	}

	private static long seconds(Duration value, long fallback) {
		if (value == null || value.isNegative() || value.isZero()) return fallback;
		return Math.max(1, value.toSeconds());
	}
}
