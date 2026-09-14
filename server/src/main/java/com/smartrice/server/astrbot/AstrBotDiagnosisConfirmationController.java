package com.smartrice.server.astrbot;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/astrbot/diagnosis")
public class AstrBotDiagnosisConfirmationController {

	private static final String TOKEN_HEADER = "X-AstrBot-Token";
	private final AstrBotDiagnosisConfirmationService service;

	public AstrBotDiagnosisConfirmationController(AstrBotDiagnosisConfirmationService service) {
		this.service = service;
	}

	@PostMapping("/confirm")
	public AstrBotDiagnosisConfirmationResponse confirm(
			@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@Valid @RequestBody AstrBotDiagnosisConfirmationRequest request) {
		return service.confirm(token, request);
	}
}
