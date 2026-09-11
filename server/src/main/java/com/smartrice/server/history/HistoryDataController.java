package com.smartrice.server.history;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/history")
public class HistoryDataController {

	private final HistoryDataService service;

	public HistoryDataController(HistoryDataService service) {
		this.service = service;
	}

	@GetMapping("/range")
	public HistoryRangeResponse range(@RequestParam String stationId) {
		return service.range(stationId);
	}

	@GetMapping("/daily")
	public HistoryDailyResponse daily(
			@RequestParam String stationId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return service.daily(stationId, date);
	}
}
