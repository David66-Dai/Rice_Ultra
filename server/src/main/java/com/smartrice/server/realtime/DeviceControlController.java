package com.smartrice.server.realtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
public class DeviceControlController {

	private final DeviceCommandService commands;

	public DeviceControlController(DeviceCommandService commands) {
		this.commands = commands;
	}

	@GetMapping("/state")
	public DeviceStatusResponse state(@RequestParam String stationId) {
		return commands.status(stationId);
	}

	@PostMapping("/control")
	public DeviceControlResponse control(@RequestBody DeviceControlRequest request) {
		return commands.apply(request.stationId(), request.device(), request.enabled(), "operator");
	}
}
