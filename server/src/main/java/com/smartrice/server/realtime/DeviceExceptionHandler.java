package com.smartrice.server.realtime;

import com.smartrice.server.astrbot.AstrBotControlController;
import com.smartrice.server.astrbot.AstrBotAgricultureQueryController;
import com.smartrice.server.astrbot.AstrBotDiagnosisConfirmationController;
import com.smartrice.server.notifications.NotificationController;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {
	DeviceControlController.class, NotificationController.class, AstrBotControlController.class,
	AstrBotAgricultureQueryController.class, AstrBotDiagnosisConfirmationController.class
})
public class DeviceExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handle(ResponseStatusException exception) {
		String code = switch (exception.getStatusCode().value()) {
			case 401 -> "integration_unauthorized";
			case 403 -> "device_forbidden";
			case 409 -> "device_conflict";
			case 503 -> "device_unavailable";
			default -> "invalid_device_request";
		};
		return ResponseEntity.status(exception.getStatusCode()).body(Map.of("code", code,
			"message", exception.getReason() == null ? "设备请求失败" : exception.getReason()));
	}
}
