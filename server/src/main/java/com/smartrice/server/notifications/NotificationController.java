package com.smartrice.server.notifications;

import com.smartrice.server.realtime.DeviceActivityService;
import com.smartrice.server.realtime.DeviceSyncResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final DeviceActivityService activity;

	public NotificationController(DeviceActivityService activity) {
		this.activity = activity;
	}

	public record ReadRequest(@NotNull @Min(0) Long throughId) {
	}

	@PostMapping("/read")
	public DeviceSyncResponse read(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReadRequest request) {
		return activity.markRead(jwt, request.throughId());
	}
}
