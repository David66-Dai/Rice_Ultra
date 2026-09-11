package com.smartrice.server.realtime;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceCommandService {

	public static final String PUMP = "pump";
	public static final String LAMP = "lamp";
	private static final Pattern STATION_ID = Pattern.compile("S(?:0[1-9]|10)");
	private static final String CONTROL_STATION = "S01";

	private final StationDeviceRepository devices;
	private final ObjectProvider<DeviceActuator> actuators;

	public DeviceCommandService(StationDeviceRepository devices, ObjectProvider<DeviceActuator> actuators) {
		this.devices = devices;
		this.actuators = actuators;
	}

	public DeviceStatusResponse status(String stationId) {
		String station = validateStation(stationId);
		StationDevice pump = devices.findByStationIdAndDevice(station, PUMP).orElse(null);
		StationDevice lamp = devices.findByStationIdAndDevice(station, LAMP).orElse(null);
		return new DeviceStatusResponse(
			station,
			pump != null && pump.isEnabled(),
			lamp != null && lamp.isEnabled(),
			latest(pump == null ? null : pump.getUpdatedAt(), lamp == null ? null : lamp.getUpdatedAt())
		);
	}

	public DeviceControlResponse ensureEnabled(String stationId, String device, String actor) {
		return apply(stationId, device, true, actor);
	}

	public DeviceControlResponse apply(String stationId, String device, boolean enabled, String actor) {
		String station = validateStation(stationId);
		if (!CONTROL_STATION.equals(station)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前仅 S01 支持设备控制");
		}
		String kind = validateDevice(device);
		if (isEnabled(station, kind) == enabled) {
			return new DeviceControlResponse(station, kind, enabled, commandName(kind, enabled), Instant.now());
		}
		send(commandCode(kind, enabled));
		Instant now = Instant.now();
		StationDevice row = devices.findByStationIdAndDevice(station, kind).orElseGet(StationDevice::new);
		row.setStationId(station);
		row.setDevice(kind);
		row.setEnabled(enabled);
		row.setUpdatedAt(now);
		row.setUpdatedBy(truncate(actor, 64));
		devices.save(row);
		return new DeviceControlResponse(station, kind, enabled, commandName(kind, enabled), now);
	}

	private boolean isEnabled(String stationId, String device) {
		return devices.findByStationIdAndDevice(stationId, device)
			.map(StationDevice::isEnabled)
			.orElse(false);
	}

	private void send(int commandCode) {
		DeviceActuator actuator = actuators.getIfAvailable();
		if (actuator == null) {
			return;
		}
		try {
			actuator.sendCommand(commandCode);
		}
		catch (IllegalStateException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
		}
	}

	private static String validateStation(String stationId) {
		String normalized = stationId == null ? "" : stationId.trim().toUpperCase(Locale.ROOT);
		if (!STATION_ID.matcher(normalized).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId 必须为 S01-S10");
		}
		return normalized;
	}

	private static String validateDevice(String device) {
		String kind = device == null ? "" : device.trim().toLowerCase(Locale.ROOT);
		if (PUMP.equals(kind) || LAMP.equals(kind)) {
			return kind;
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device 必须为 pump 或 lamp");
	}

	private static int commandCode(String device, boolean enabled) {
		return switch (device) {
			case PUMP -> enabled ? 0x01 : 0x03;
			case LAMP -> enabled ? 0x02 : 0x04;
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device 必须为 pump 或 lamp");
		};
	}

	private static String commandName(String device, boolean enabled) {
		return "FA%02X".formatted(commandCode(device, enabled));
	}

	private static Instant latest(Instant left, Instant right) {
		if (left == null) {
			return right;
		}
		if (right == null) {
			return left;
		}
		return left.isAfter(right) ? left : right;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}
