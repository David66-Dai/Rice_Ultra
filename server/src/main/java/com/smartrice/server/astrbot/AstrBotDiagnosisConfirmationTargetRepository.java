package com.smartrice.server.astrbot;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AstrBotDiagnosisConfirmationTargetRepository
		extends JpaRepository<AstrBotDiagnosisConfirmationTarget, AstrBotDiagnosisConfirmationTarget.Key> {

	List<AstrBotDiagnosisConfirmationTarget> findByKeyConfirmationId(String confirmationId);
}
