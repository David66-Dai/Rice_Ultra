package com.smartrice.server.history;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Preserve useful history errors without returning Hive SQL, addresses or credentials. */
@RestControllerAdvice(assignableTypes = HistoryDataController.class)
public class HistoryExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
		String code = switch (ex.getStatusCode().value()) {
			case 400 -> "invalid_history_request";
			case 404 -> "history_not_found";
			default -> "hive_history_unavailable";
		};
		return ResponseEntity.status(ex.getStatusCode()).body(Map.of("code", code,
			"message", ex.getReason() == null ? "历史数据暂不可用" : ex.getReason()));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, String>> invalidDate(MethodArgumentTypeMismatchException ex) {
		return ResponseEntity.badRequest().body(Map.of("code", "invalid_history_request",
			"message", "日期格式应为 YYYY-MM-DD"));
	}
}
