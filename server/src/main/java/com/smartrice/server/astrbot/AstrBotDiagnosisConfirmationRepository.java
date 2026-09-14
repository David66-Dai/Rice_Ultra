package com.smartrice.server.astrbot;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AstrBotDiagnosisConfirmationRepository
		extends JpaRepository<AstrBotDiagnosisConfirmation, String> {

	Optional<AstrBotDiagnosisConfirmation> findByDiagnosisId(long diagnosisId);
	List<AstrBotDiagnosisConfirmation> findByStatusAndDeliveryStatusIn(
		String status, Collection<String> deliveryStatuses);
}
