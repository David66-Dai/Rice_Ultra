package com.smartrice.server.ai;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = AiAnalysisController.class)
public class AiExceptionHandler {
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
		return ResponseEntity.status(ex.getStatusCode()).body(Map.of("code", "ai_request_failed", "message",
			ex.getReason() == null ? "AI 服务暂不可用" : ex.getReason()));
	}
	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
	public ResponseEntity<Map<String, String>> invalid(Exception ex) {
		return ResponseEntity.badRequest().body(Map.of("code", "invalid_ai_request", "message", "请提供有效站点、YYYY-MM-DD 日期、7/14/30 天窗口及生长周期"));
	}
}
