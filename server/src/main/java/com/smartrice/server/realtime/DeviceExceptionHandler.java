package com.smartrice.server.realtime;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = DeviceControlController.class)
public class DeviceExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
		String code = ex.getStatusCode().value() == 400 ? "invalid_device_request" : "device_unavailable";
		return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
			"code", code,
			"message", ex.getReason() == null ? "设备控制失败" : ex.getReason()
		));
	}
}
