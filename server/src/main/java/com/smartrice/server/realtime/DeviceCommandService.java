package com.smartrice.server.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single low-level path for device state and actuator writes.
 * Authorization, notifications and client synchronization live in {@link DeviceActivityService}.
 */
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
			pump != null && Boolean.TRUE.equals(pump.isEnabled()),
			lamp != null && Boolean.TRUE.equals(lamp.isEnabled()),
			latest(pump == null ? null : pump.getUpdatedAt(), lamp == null ? null : lamp.getUpdatedAt())
		);
	}

	public List<DeviceState> states() {
		return List.of(state(CONTROL_STATION, PUMP), state(CONTROL_STATION, LAMP));
	}

	public DeviceState state(String stationId, String device) {
		String station = validateControlStation(stationId);
		String kind = validateDevice(device);
		return devices.findByStationIdAndDevice(station, kind)
			.map(DeviceCommandService::toState)
			.orElseGet(() -> new DeviceState(station, kind, null, 0, null, null));
	}

	public boolean available() {
		DeviceActuator actuator = actuators.getIfAvailable();
		return actuator != null && actuator.isAvailable();
	}

	/** Must be called inside the caller's database transaction. */
	public DeviceControlResponse apply(String stationId, String device, boolean enabled,
			long expectedRevision, String actor, Runnable beforeWrite) {
		String station = validateControlStation(stationId);
		String kind = validateDevice(device);
		StationDevice row = devices.findByStationIdAndDevice(station, kind).orElse(null);
		DeviceState previous = row == null
			? new DeviceState(station, kind, null, 0, null, null)
			: toState(row);
		if (previous.revision() != expectedRevision) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "设备状态已被其他操作更新，请同步最新状态后重试");
		}
		DeviceActuator actuator = actuators.getIfAvailable();
		if (actuator == null || !actuator.isAvailable()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备串口当前未连接");
		}
		int code = commandCode(kind, enabled);
		beforeWrite.run();
		try {
			actuator.sendCommand(code);
		}
		catch (IllegalStateException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				ex.getMessage() == null ? "设备指令发送失败，请检查设备连接" : ex.getMessage(), ex);
		}
		Instant now = Instant.now();
		if (row == null) {
			row = new StationDevice();
			row.setStationId(station);
			row.setDevice(kind);
		}
		row.setEnabled(enabled);
		row.setRevision(previous.revision() + 1);
		row.setUpdatedAt(now);
		row.setUpdatedBy(truncate(actor, 64));
		StationDevice saved = devices.saveAndFlush(row);
		return response(toState(saved), code);
	}

	/** Persists an uncertain result after a possibly partial hardware write. */
	public DeviceState markUnknown(String stationId, String device, String actor,
			long previousRevision, Instant attemptedAt) {
		String station = validateControlStation(stationId);
		String kind = validateDevice(device);
		StationDevice row = devices.findByStationIdAndDevice(station, kind).orElse(null);
		long uncertainRevision = previousRevision + 1;
		if (row != null && row.getRevision() > uncertainRevision) {
			return toState(row);
		}
		if (row == null) {
			row = new StationDevice();
			row.setStationId(station);
			row.setDevice(kind);
		}
		row.setEnabled(null);
		row.setRevision(Math.max(row.getRevision(), uncertainRevision));
		row.setUpdatedAt(attemptedAt);
		row.setUpdatedBy(truncate(actor, 64));
		return toState(devices.saveAndFlush(row));
	}

	public DeviceControlResponse noOpResponse(String stationId, String device, boolean enabled) {
		DeviceState current = state(stationId, device);
		DeviceState effective = current.enabled() == null && !enabled
			? new DeviceState(current.stationId(), current.device(), false, current.revision(),
				current.updatedAt(), current.updatedBy())
			: current;
		return response(effective, commandCode(effective.device(), enabled));
	}

	private static String validateControlStation(String stationId) {
		String station = validateStation(stationId);
		if (!CONTROL_STATION.equals(station)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前仅 S01 支持设备控制");
		}
		return station;
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

	private static DeviceState toState(StationDevice row) {
		return new DeviceState(row.getStationId(), row.getDevice(), row.isEnabled(), row.getRevision(),
			row.getUpdatedAt(), row.getUpdatedBy());
	}

	private static DeviceControlResponse response(DeviceState state, int code) {
		Instant sentAt = state.updatedAt() == null ? Instant.now() : state.updatedAt();
		return new DeviceControlResponse(state.stationId(), state.device(), Boolean.TRUE.equals(state.enabled()),
			"FA%02X".formatted(code), sentAt, state);
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
