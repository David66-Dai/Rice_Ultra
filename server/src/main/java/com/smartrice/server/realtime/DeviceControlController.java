package com.smartrice.server.realtime;

import java.time.Instant;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/devices")
@ConditionalOnProperty(name = "app.realtime.serial.enabled", havingValue = "true")
public class DeviceControlController {

	private final SerialSensorCollector serialCollector;

	public DeviceControlController(SerialSensorCollector serialCollector) {
		this.serialCollector = serialCollector;
	}

	@PostMapping("/control")
	public DeviceControlResponse control(@RequestBody DeviceControlRequest request) {
		if (request.stationId() == null || !"S01".equalsIgnoreCase(request.stationId().trim())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前仅 S01 支持设备控制");
		}
		String device = request.device() == null
			? ""
			: request.device().trim().toLowerCase(Locale.ROOT);
		int commandCode = commandCode(device, request.enabled());
		try {
			serialCollector.sendCommand(commandCode);
		}
		catch (IllegalStateException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
		}
		return new DeviceControlResponse(
			"S01",
			device,
			request.enabled(),
			"FA%02X".formatted(commandCode),
			Instant.now()
		);
	}

	private static int commandCode(String device, boolean enabled) {
		return switch (device) {
			case "pump" -> enabled ? 0x01 : 0x03;
			case "lamp" -> enabled ? 0x02 : 0x04;
			default -> throw new ResponseStatusException(
				HttpStatus.BAD_REQUEST, "device 必须为 pump 或 lamp"
			);
		};
	}
}
