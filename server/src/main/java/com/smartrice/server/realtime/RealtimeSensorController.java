package com.smartrice.server.realtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api/realtime")
public class RealtimeSensorController {

	private final RealtimeSensorService service;
	private final RealtimeSensorStream stream;

	public RealtimeSensorController(RealtimeSensorService service, RealtimeSensorStream stream) {
		this.service = service;
		this.stream = stream;
	}

	/**
	 * Pass {@code after} (the newest {@code sampledAt} already rendered, or an empty string when
	 * the station has produced none) with {@code waitSeconds} to hold the request open until the
	 * next sample lands; omit them for an immediate snapshot.
	 */
	@GetMapping("/today")
	public DeferredResult<RealtimeTodayResponse> today(@RequestParam String stationId,
			@RequestParam(required = false) String after,
			@RequestParam(defaultValue = "0") int waitSeconds) {
		String station = RealtimeSensorService.validateStation(stationId);
		return stream.await(station, service.unchanged(station, after), waitSeconds, () -> service.today(station));
	}

	@GetMapping("/latest")
	public DeferredResult<RealtimeSensorReadingResponse> latest(@RequestParam String stationId,
			@RequestParam(required = false) String after,
			@RequestParam(defaultValue = "0") int waitSeconds) {
		String station = RealtimeSensorService.validateStation(stationId);
		return stream.await(station, service.unchanged(station, after), waitSeconds, () -> service.latest(station));
	}
}
