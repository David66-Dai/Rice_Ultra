package com.smartrice.server.astrbot;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/astrbot/devices")
public class AstrBotControlController {

	private static final String TOKEN_HEADER = "X-AstrBot-Token";
	private final AstrBotIntegrationService service;

	public AstrBotControlController(AstrBotIntegrationService service) {
		this.service = service;
	}

	@PostMapping("/sync")
	public AstrBotDeviceSnapshot sync(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@Valid @RequestBody AstrBotSyncRequest request) {
		return service.snapshot(token, request.identity());
	}

	@PostMapping("/control")
	public AstrBotControlResponse control(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@Valid @RequestBody AstrBotControlRequest request) {
		return service.control(token, request);
	}
}
