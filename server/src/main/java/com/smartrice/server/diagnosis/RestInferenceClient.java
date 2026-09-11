package com.smartrice.server.diagnosis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.config.AppProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RestInferenceClient implements InferenceClient {

	private static final Logger log = LoggerFactory.getLogger(RestInferenceClient.class);
	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
	private static final String BOUNDARY = "RiceInferenceBoundary";

	private final String baseUrl;
	private final ObjectMapper json;

	public RestInferenceClient(AppProperties props, ObjectMapper json) {
		this.baseUrl = props.getInference().getBaseUrl().replaceAll("/+$", "");
		this.json = json;
	}

	@Override
	public Map<String, Object> predict(String task, MultipartFile file) {
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (Exception ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取上传文件");
		}
		String filename = safeFilename(file.getOriginalFilename());
		String contentType = file.getContentType() == null || file.getContentType().isBlank()
			? "application/octet-stream"
			: file.getContentType();
		try {
			byte[] payload = multipart(filename, contentType, bytes);
			HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/predict/" + task).toURL().openConnection();
			conn.setConnectTimeout(10_000);
			conn.setReadTimeout(180_000);
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setUseCaches(false);
			conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
			conn.setRequestProperty("Accept", "application/json");
			conn.setFixedLengthStreamingMode(payload.length);
			try (OutputStream out = conn.getOutputStream()) {
				out.write(payload);
			}
			int status = conn.getResponseCode();
			InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
			String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			conn.disconnect();
			if (status >= 400) {
				log.warn("推理服务 HTTP {} {}", status, body);
				throw new ResponseStatusException(
					status == 400 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
					status == 400 ? "无法识别该图片，请换一张后重试" : "推理服务暂不可用");
			}
			Map<String, Object> result = json.readValue(body, MAP);
			if (result == null || result.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "推理服务返回空结果");
			}
			return result;
		}
		catch (ResponseStatusException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("调用推理服务失败: {}", ex.toString());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "推理服务暂不可用");
		}
	}

	static byte[] multipart(String filename, String contentType, byte[] bytes) throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.US_ASCII));
		body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
			.getBytes(StandardCharsets.US_ASCII));
		body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		body.write(bytes);
		body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		return body.toByteArray();
	}

	private static String safeFilename(String filename) {
		if (filename == null || filename.isBlank()) {
			return "upload.jpg";
		}
		return filename.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
	}
}
