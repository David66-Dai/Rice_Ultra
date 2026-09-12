package com.smartrice.server.diagnosis;

import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public interface InferenceClient {

	Map<String, Object> predict(String task, MultipartFile file);
}
