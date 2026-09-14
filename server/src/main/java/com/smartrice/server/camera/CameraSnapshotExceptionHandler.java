package com.smartrice.server.camera;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = CameraSnapshotController.class)
public class CameraSnapshotExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
		String code = ex.getStatusCode().value() == 400 ? "invalid_camera_request" : "camera_unavailable";
		return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
			"code", code,
			"message", ex.getReason() == null ? "抓拍失败" : ex.getReason()
		));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<Map<String, String>> missing(MissingServletRequestParameterException ex) {
		return ResponseEntity.badRequest().body(Map.of(
			"code", "invalid_camera_request",
			"message", "请提供摄像头抓拍地址"
		));
	}
}
