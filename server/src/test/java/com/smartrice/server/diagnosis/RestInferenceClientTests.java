package com.smartrice.server.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class RestInferenceClientTests {

	@Test
	void postsNamedFilePartWithoutTransferEncoding() throws Exception {
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/predict/leaf", exchange -> {
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
			byte[] response = """
				{"task":"leaf","label":"Healthy Leaf","label_zh":"健康叶片","confidence":0.91}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			AppProperties props = new AppProperties();
			props.getInference().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
			RestInferenceClient client = new RestInferenceClient(props, new ObjectMapper());
			Map<String, Object> result = client.predict("leaf",
				new MockMultipartFile("file", "leaf.png", "image/png", new byte[] {1, 2, 3, 4}));
			assertThat(contentType.get()).isEqualTo("multipart/form-data; boundary=RiceInferenceBoundary");
			assertThat(body.get()).contains("name=\"file\"");
			assertThat(body.get()).contains("filename=\"leaf.png\"");
			assertThat(body.get()).doesNotContain("Content-Transfer-Encoding");
			assertThat(result.get("label")).isEqualTo("Healthy Leaf");
		}
		finally {
			server.stop(0);
		}
	}
}
