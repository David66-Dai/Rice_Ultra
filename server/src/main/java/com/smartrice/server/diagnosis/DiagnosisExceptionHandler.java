package com.smartrice.server.diagnosis;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = DiagnosisController.class)
public class DiagnosisExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
		String code = ex.getStatusCode().value() == 400 ? "invalid_diagnosis_request" : "inference_unavailable";
		return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
			"code", code,
			"message", ex.getReason() == null ? "识别失败" : ex.getReason()
		));
	}

	@ExceptionHandler({
		MissingServletRequestParameterException.class,
		MissingServletRequestPartException.class
	})
	public ResponseEntity<Map<String, String>> missing(Exception ex) {
		return ResponseEntity.badRequest().body(Map.of(
			"code", "invalid_diagnosis_request",
			"message", "请选择站点并上传图片文件"
		));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException ex) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
			"code", "invalid_diagnosis_request",
			"message", "图片过大，请上传不超过 20MB 的文件"
		));
	}
}
