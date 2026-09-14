package com.smartrice.server.diagnosis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionDiagnosisRepository extends JpaRepository<InspectionDiagnosis, Long> {

	Optional<InspectionDiagnosis> findFirstByStationIdAndTaskOrderByCreatedAtDesc(String stationId, String task);

	/** Half-open instant range; callers convert the field day in Asia/Shanghai before querying. */
	List<InspectionDiagnosis> findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
		String stationId, Instant start, Instant end, Pageable pageable);

	List<InspectionDiagnosis> findByAlertLevelInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
		List<String> alertLevels, Instant start, Instant end, Pageable pageable);

	List<InspectionDiagnosis> findByStationIdAndAlertLevelInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
		String stationId, List<String> alertLevels, Instant start, Instant end, Pageable pageable);
}
