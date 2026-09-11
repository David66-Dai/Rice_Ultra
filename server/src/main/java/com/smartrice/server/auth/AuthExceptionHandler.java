package com.smartrice.server.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把登录相关异常与参数校验错误统一转成 {code, message} JSON。 */
@RestControllerAdvice
public class AuthExceptionHandler {

	@ExceptionHandler(AuthException.class)
	public ResponseEntity<Map<String, Object>> handleAuth(AuthException ex) {
		return ResponseEntity.status(ex.getStatus()).body(body(ex.getCode(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(FieldError::getDefaultMessage)
			.orElse("请求参数不合法");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("validation_failed", message));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("bad_request", "请求体格式不正确"));
	}

	static Map<String, Object> body(String code, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", code);
		body.put("message", message);
		body.put("timestamp", Instant.now().toString());
		return body;
	}
}
