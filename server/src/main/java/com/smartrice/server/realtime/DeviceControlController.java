package com.smartrice.server.realtime;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api/devices")
public class DeviceControlController {

	private final DeviceActivityService activity;

	public DeviceControlController(DeviceActivityService activity) {
		this.activity = activity;
	}

	/** Compatibility state endpoint used by the diagnosis-aware mainline UI. */
	@GetMapping("/state")
	public DeviceStatusResponse state(@RequestParam String stationId) {
		return activity.status(stationId);
	}

	@PostMapping("/control")
	public DeviceControlResponse control(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody DeviceControlRequest request) {
		return activity.control(jwt, request);
	}

	/** Shared authenticated snapshot used by the permission-aware UI and AstrBot adapter. */
	@GetMapping("/sync")
	public DeferredResult<DeviceSyncResponse> sync(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String after,
			@RequestParam(defaultValue = "25") int waitSeconds) {
		return activity.sync(jwt, after, waitSeconds);
	}
}
