package com.smartrice.server.diagnosis;

import java.time.Instant;

/**
 * Builds inspection rows for tests in other packages. The entity keeps the protected no-arg
 * constructor JPA expects, so anything outside this package needs a factory like this one.
 */
public final class InspectionDiagnosisFixtures {

	private InspectionDiagnosisFixtures() {
	}

	public static InspectionDiagnosis row(String stationId, String task, String label, String labelZh,
			int detectionCount, String alertLevel, String resultJson, Instant createdAt) {
		var diagnosis = new InspectionDiagnosis();
		diagnosis.setStationId(stationId);
		diagnosis.setTask(task);
		diagnosis.setLabel(label);
		diagnosis.setLabelZh(labelZh);
		diagnosis.setDetectionCount(detectionCount);
		diagnosis.setAlertLevel(alertLevel);
		diagnosis.setResultJson(resultJson);
		diagnosis.setCreatedAt(createdAt);
		return diagnosis;
	}
}
