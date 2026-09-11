package com.smartrice.server.web;

import com.smartrice.server.config.AppProperties;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

	private final RestClient restClient;
	private final AppProperties props;

	public DiagnosisController(RestClient.Builder builder, AppProperties props) {
		this.props = props;
		this.restClient = builder.baseUrl(props.getInference().getBaseUrl()).build();
	}

	@PostMapping(value = "/leaf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> diagnoseLeaf(@RequestPart("file") MultipartFile file) {
		return forward("/predict/leaf", file);
	}

	@PostMapping(value = "/pest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> diagnosePest(@RequestPart("file") MultipartFile file) {
		return forward("/predict/pest", file);
	}

	private ResponseEntity<?> forward(String path, MultipartFile file) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", file.getResource());

		try {
			Map<?, ?> result = restClient.post()
				.uri(path)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.body(Map.class);
			return ResponseEntity.ok(result);
		}
		catch (Exception ex) {
			return ResponseEntity.status(502).body(Map.of(
				"error", "inference_unavailable",
				"message", ex.getMessage(),
				"inferenceBaseUrl", props.getInference().getBaseUrl()
			));
		}
	}
}
