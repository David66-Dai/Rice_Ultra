package com.smartrice.server.astrbot;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/astrbot/agriculture")
public class AstrBotAgricultureQueryController {

	private static final String TOKEN_HEADER = "X-AstrBot-Token";
	private final AstrBotAgricultureQueryService service;

	public AstrBotAgricultureQueryController(AstrBotAgricultureQueryService service) {
		this.service = service;
	}

	@PostMapping("/status")
	public AstrBotQueryStatus status(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@Valid @RequestBody AstrBotSyncRequest request) {
		return service.status(token, request.identity());
	}

	@PostMapping("/data")
	public AstrBotAgricultureQueryResponse data(
			@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@Valid @RequestBody AstrBotAgricultureQueryRequest request) {
		return service.data(token, request);
	}

	@PostMapping("/alerts")
	public AstrBotAlertQueryResponse alerts(
			@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@Valid @RequestBody AstrBotAgricultureQueryRequest request) {
		return service.alerts(token, request);
	}
}
