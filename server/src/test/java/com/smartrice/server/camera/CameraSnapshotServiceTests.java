package com.smartrice.server.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CameraSnapshotServiceTests {

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, 0x10, 0x20, (byte) 0xFF, (byte) 0xD9 };

	private static CameraProxyProperties loopbackProps() {
		CameraProxyProperties props = new CameraProxyProperties();
		props.setAllowedCidrs("127.0.0.0/8");
		return props;
	}

	private static HttpServer serve(String path, byte[] body, String contentType, int status) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(path, exchange -> {
			if (contentType != null) {
				exchange.getResponseHeaders().add("Content-Type", contentType);
			}
			exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		server.start();
		return server;
	}

	@Test
	void returnsSnapshotBytesFromAllowedHost() throws Exception {
		HttpServer server = serve("/snapshot.jpg", JPEG, "image/jpeg", 200);
		try {
			CameraSnapshotService service = new CameraSnapshotService(loopbackProps());
			CameraSnapshot snapshot = service.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/snapshot.jpg");
			assertThat(snapshot.bytes()).isEqualTo(JPEG);
			assertThat(snapshot.contentType()).startsWith("image/jpeg");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void sendsBasicAuthFromUrlCredentials() throws Exception {
		AtomicReference<String> authorization = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/cgi-bin/snapshot.cgi", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
			exchange.sendResponseHeaders(200, JPEG.length);
			exchange.getResponseBody().write(JPEG);
			exchange.close();
		});
		server.start();
		try {
			CameraSnapshotService service = new CameraSnapshotService(loopbackProps());
			service.fetch("http://admin:123456@127.0.0.1:" + server.getAddress().getPort() + "/cgi-bin/snapshot.cgi");
			assertThat(authorization.get())
				.isEqualTo("Basic " + Base64.getEncoder().encodeToString("admin:123456".getBytes(StandardCharsets.UTF_8)));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void reportsCameraLoginAndUpstreamErrors() throws Exception {
		HttpServer unauthorized = serve("/snapshot.jpg", new byte[0], null, 401);
		try {
			CameraSnapshotService service = new CameraSnapshotService(loopbackProps());
			assertThatThrownBy(() -> service.fetch("http://127.0.0.1:" + unauthorized.getAddress().getPort() + "/snapshot.jpg"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("摄像头要求登录");
		}
		finally {
			unauthorized.stop(0);
		}

		HttpServer html = serve("/", "<html>login</html>".getBytes(StandardCharsets.UTF_8), "text/html", 200);
		try {
			CameraSnapshotService service = new CameraSnapshotService(loopbackProps());
			assertThatThrownBy(() -> service.fetch("http://127.0.0.1:" + html.getAddress().getPort() + "/"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("返回的不是图片");
		}
		finally {
			html.stop(0);
		}
	}

	@Test
	void rejectsHostsOutsideTheAllowedRanges() {
		CameraSnapshotService service = new CameraSnapshotService(new CameraProxyProperties());
		assertThatThrownBy(() -> service.fetch("http://127.0.0.1:8080/snapshot.jpg"))
			.isInstanceOf(ResponseStatusException.class)
			.extracting(error -> ((ResponseStatusException) error).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void rejectsNonHttpAndMalformedUrls() {
		CameraSnapshotService service = new CameraSnapshotService(loopbackProps());
		assertThatThrownBy(() -> service.fetch("rtsp://127.0.0.1:554/stream"))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("只支持 http/https");
		assertThatThrownBy(() -> service.fetch("file:///etc/passwd"))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("只支持 http/https");
		assertThatThrownBy(() -> service.fetch("  "))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("请提供摄像头抓拍地址");
	}

	@Test
	void keepsOnlyTheFirstFrameOfAnMjpegStream() throws Exception {
		byte[] stream = new byte[] {
			0x2D, 0x2D, 0x62, 0x0D, 0x0A,
			(byte) 0xFF, (byte) 0xD8, 0x01, (byte) 0xFF, (byte) 0xD9,
			0x2D, 0x2D, 0x62, 0x0D, 0x0A,
			(byte) 0xFF, (byte) 0xD8, 0x02, (byte) 0xFF, (byte) 0xD9,
		};
		CameraSnapshotService service = new CameraSnapshotService(loopbackProps());
		byte[] frame = service.firstJpegFrame(new ByteArrayInputStream(stream));
		assertThat(frame).isEqualTo(new byte[] { (byte) 0xFF, (byte) 0xD8, 0x01, (byte) 0xFF, (byte) 0xD9 });
	}

	@Test
	void cidrRangeMatchesOnlyInsideTheNetwork() throws Exception {
		CidrRange range = CidrRange.parse("192.168.1.0/24");
		assertThat(range.contains(java.net.InetAddress.getByName("192.168.1.64"))).isTrue();
		assertThat(range.contains(java.net.InetAddress.getByName("192.168.2.64"))).isFalse();
		assertThat(CidrRange.parse("10.0.0.0/8").contains(java.net.InetAddress.getByName("10.9.9.9"))).isTrue();
		assertThat(CidrRange.parse("172.16.0.0/12").contains(java.net.InetAddress.getByName("172.32.0.1"))).isFalse();
		assertThat(CidrRange.parse("192.168.1.64").contains(java.net.InetAddress.getByName("192.168.1.64"))).isTrue();
		assertThatThrownBy(() -> CidrRange.parse("camera.local/24")).isInstanceOf(IllegalArgumentException.class);
	}
}
