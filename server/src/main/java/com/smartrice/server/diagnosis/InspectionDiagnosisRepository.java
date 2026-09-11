package com.smartrice.server.diagnosis;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionDiagnosisRepository extends JpaRepository<InspectionDiagnosis, Long> {

	Optional<InspectionDiagnosis> findFirstByStationIdAndTaskOrderByCreatedAtDesc(String stationId, String task);
}
