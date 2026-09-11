package com.smartrice.server.realtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime")
public class RealtimeSensorController {

	private final RealtimeSensorService service;

	public RealtimeSensorController(RealtimeSensorService service) {
		this.service = service;
	}

	@GetMapping("/today")
	public RealtimeTodayResponse today(@RequestParam String stationId) {
		return service.today(stationId);
	}

	@GetMapping("/latest")
	public RealtimeSensorReadingResponse latest(@RequestParam String stationId) {
		return service.latest(stationId);
	}
}
