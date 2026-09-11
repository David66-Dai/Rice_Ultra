package com.smartrice.server.hdfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WebHdfsClientTests {

	private static final String RELATIVE = "point_1/output_20260911_1630.json";
	private static final String ABSOLUTE = "/rice/output/" + RELATIVE;

	@Test
	void createUsesRealTwoPhaseUploadThenRenameAndOpenFollowsTheDataNode() throws Exception {
		try (Fixture fixture = new Fixture()) {
			byte[] content = "{\"summary\":\"完整报告\"}".getBytes(StandardCharsets.UTF_8);
			assertThat(fixture.client.configured()).isTrue();
			assertThat(fixture.client.absolutePath(RELATIVE)).isEqualTo(ABSOLUTE);
			fixture.client.writeAtomic(RELATIVE, content);
			assertThat(fixture.handshakeBodies).hasSize(1);
			assertThat(fixture.handshakeBodies.getFirst()).isEmpty();
			assertThat(fixture.uploadBodies).hasSize(1);
			assertThat(fixture.uploadBodies.getFirst()).isEqualTo(content);
			assertThat(fixture.files).containsOnlyKeys(ABSOLUTE);
			assertThat(fixture.client.read(RELATIVE)).hasValueSatisfying(value -> assertThat(value).isEqualTo(content));
			assertThat(fixture.requests.stream().filter(request -> request.op().equals("CREATE"))).allSatisfy(request -> {
				assertThat(request.query()).containsEntry("overwrite", "false").containsEntry("permission", "766");
				assertThat(request.authHeader()).isNull();
			});
			assertThat(fixture.requests.stream().filter(request -> request.op().equals("MKDIRS")))
				.singleElement().satisfies(request -> assertThat(request.query()).containsEntry("permission", "755"));
			assertThat(fixture.permissions).containsEntry("/rice/output/point_1", "755").containsEntry(ABSOLUTE, "766");
			assertThat(fixture.requests.stream().filter(request -> request.op().equals("SETPERMISSION"))).hasSize(2);
			assertThat(fixture.client.list("point_1")).containsExactly(new WebHdfsClient.FileEntry("output_20260911_1630.json", false, 12345));
		}
	}

	@Test
	void existingReportIs409AndRemainsUntouchedWithoutUploading() throws Exception {
		try (Fixture fixture = new Fixture()) {
			byte[] original = "old report".getBytes(StandardCharsets.UTF_8);
			fixture.files.put(ABSOLUTE, original);
			assertThatThrownBy(() -> fixture.client.writeAtomic(RELATIVE, new byte[] {1}))
				.isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
					assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(ex.getReason()).contains("已存在");
				});
			assertThat(fixture.files.get(ABSOLUTE)).isSameAs(original);
			assertThat(fixture.uploadBodies).isEmpty();
		}
	}

	@Test
	void failedRenameDoesNotPublishTheTargetAndOnlyRemovesItsRandomTemporaryFile() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.renameFails = true;
			fixture.files.put("/rice/output/point_1/.unrelated.tmp", new byte[] {7});
			assertUnavailable(() -> fixture.client.writeAtomic(RELATIVE, new byte[] {1, 2}));
			assertThat(fixture.files).containsOnlyKeys("/rice/output/point_1/.unrelated.tmp");
			assertThat(fixture.client.list("point_1")).isEmpty();
			assertThat(fixture.requests.stream().filter(request -> request.op().equals("DELETE")))
				.singleElement().satisfies(request -> {
					assertThat(request.path()).startsWith("/rice/output/point_1/.upload-").endsWith(".tmp");
					assertThat(request.query()).containsEntry("recursive", "false");
				});
		}
	}

	@Test
	void aTargetCreatedDuringUploadWinsAndNeverGetsOverwritten() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.competingTargetOnUpload = true;
			assertThatThrownBy(() -> fixture.client.writeAtomic(RELATIVE, new byte[] {1}))
				.isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
			assertThat(fixture.files.get(ABSOLUTE)).isEqualTo("competing".getBytes(StandardCharsets.UTF_8));
			assertThat(fixture.files).hasSize(1);
		}
	}

	@Test
	void missingReadAndListAreEmptyButAuthenticationAndServerErrorsAreUnavailable() throws Exception {
		try (Fixture fixture = new Fixture()) {
			assertThat(fixture.client.read(RELATIVE)).isEmpty();
			assertThat(fixture.client.list("missing")).isEmpty();
			for (int status : new int[] {401, 403, 500, 503}) {
				fixture.forcedStatus = status;
				assertUnavailable(() -> fixture.client.read(RELATIVE));
				assertUnavailable(() -> fixture.client.list(""));
				assertUnavailable(() -> fixture.client.writeAtomic(RELATIVE, new byte[] {1}));
			}
		}
	}

	@Test
	void fileAndMetadataBodiesAreBoundedAndOversizedWritesNeverReachTheServer() throws Exception {
		try (Fixture fixture = new Fixture()) {
			byte[] oversized = new byte[WebHdfsClient.MAX_BYTES + 1];
			assertUnavailable(() -> fixture.client.writeAtomic(RELATIVE, oversized));
			assertThat(fixture.requests).isEmpty();
			fixture.files.put(ABSOLUTE, oversized);
			assertUnavailable(() -> fixture.client.read(RELATIVE));
			fixture.largeListing = true;
			assertUnavailable(() -> fixture.client.list(""));
		}
	}

	@Test
	void timeoutCoversTheWholeBodyAfterHeadersWereAlreadySent() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.properties.setTimeoutSeconds(1);
			fixture.files.put(ABSOLUTE, new byte[] {1});
			fixture.stalledBody = true;
			Instant start = Instant.now();
			assertUnavailable(() -> fixture.client.read(RELATIVE));
			assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(3));
		}
	}

	@Test
	void traversalAndUrlInjectionAreRejectedBeforeAnyNetworkRequest() throws Exception {
		try (Fixture fixture = new Fixture()) {
			for (String path : new String[] {"../secret", "/outside", "point_1/../report.json", "point_1/%2e%2e/report.json",
					"point_1//report.json", "point_1\\report.json", "http://other/report", "point_1/a?op=DELETE", "point_1/a#fragment", ".snapshot/file"}) {
				assertUnavailable(() -> fixture.client.read(path));
				assertUnavailable(() -> fixture.client.list(path));
				assertUnavailable(() -> fixture.client.absolutePath(path));
			}
			assertUnavailable(() -> fixture.client.writeAtomic("", new byte[] {1}));
			assertThat(fixture.requests).isEmpty();
			assertThat(fixture.client.list("")).isEmpty();
		}
	}

	@Test
	void redirectsCannotChangeThePathOperationOrAuthentication() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.files.put(ABSOLUTE, new byte[] {1});
			for (String suffix : new String[] {"/webhdfs/v1/outside?op=OPEN&user.name=root",
					"/webhdfs/v1" + ABSOLUTE + "?op=DELETE&user.name=root",
					"/webhdfs/v1" + ABSOLUTE + "?op=OPEN&user.name=another",
					"/webhdfs/v1" + ABSOLUTE + "?op=OPEN&user.name=root&doas=other"}) {
				fixture.redirectOverride = fixture.dataNodeOrigin() + suffix;
				assertUnavailable(() -> fixture.client.read(RELATIVE));
			}
			assertThat(fixture.requests).noneMatch(request -> request.dataNode());
		}
	}

	@Test
	void explicitDataNodeHostOverridePreservesItsPortAndNeverUsesTheNameNodePort() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.properties.setDataNodeHostOverrides(Map.of("confirmed-datanode.invalid", "127.0.0.1"));
			fixture.advertisedDataNodeHost = "confirmed-datanode.invalid";
			fixture.client.writeAtomic(RELATIVE, new byte[] {9});
			assertThat(fixture.client.read(RELATIVE)).hasValueSatisfying(value -> assertThat(value).containsExactly((byte) 9));
			assertThat(fixture.uploadBodies).hasSize(1);
		}
	}

	private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(ex.getReason()).contains("HDFS").doesNotContain("SECRET_CANARY", "http://", "user.name");
			assertThat(ex.getCause()).isNull();
		});
	}

	private record Request(String path, String op, Map<String, String> query, boolean dataNode, String authHeader) { }

	private static final class Fixture implements AutoCloseable {
		final ObjectMapper mapper = new ObjectMapper();
		final HdfsProperties properties = new HdfsProperties();
		final Map<String, byte[]> files = new ConcurrentHashMap<>();
		final Map<String, String> permissions = new ConcurrentHashMap<>();
		final Set<String> directories = ConcurrentHashMap.newKeySet();
		final List<Request> requests = new CopyOnWriteArrayList<>();
		final List<byte[]> handshakeBodies = new CopyOnWriteArrayList<>();
		final List<byte[]> uploadBodies = new CopyOnWriteArrayList<>();
		final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		final HttpServer nameNode;
		final HttpServer dataNode;
		final WebHdfsClient client;
		volatile int forcedStatus;
		volatile boolean renameFails;
		volatile boolean largeListing;
		volatile boolean stalledBody;
		volatile boolean competingTargetOnUpload;
		volatile String redirectOverride;
		volatile String advertisedDataNodeHost = "127.0.0.1";

		Fixture() throws IOException {
			nameNode = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			dataNode = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			nameNode.setExecutor(executor);
			dataNode.setExecutor(executor);
			nameNode.createContext("/", exchange -> handle(exchange, false));
			dataNode.createContext("/", exchange -> handle(exchange, true));
			nameNode.start();
			dataNode.start();
			directories.add("/rice/output");
			properties.setWebUrl("http://127.0.0.1:" + nameNode.getAddress().getPort());
			client = new WebHdfsClient(properties, mapper);
		}

		String dataNodeOrigin() { return "http://" + advertisedDataNodeHost + ":" + dataNode.getAddress().getPort(); }

		void handle(HttpExchange exchange, boolean isDataNode) {
			try {
				Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
				String path = exchange.getRequestURI().getPath().substring("/webhdfs/v1".length());
				String op = query.get("op");
				requests.add(new Request(path, op, query, isDataNode, exchange.getRequestHeaders().getFirst("Authorization")));
				if (forcedStatus != 0) { reply(exchange, forcedStatus, "SECRET_CANARY remote error"); return; }
				if (isDataNode) {
					if ("CREATE".equals(op)) {
						byte[] body = exchange.getRequestBody().readAllBytes();
						uploadBodies.add(body);
						if (files.putIfAbsent(path, body) != null) { reply(exchange, 403, "exists"); return; }
						permissions.put(path, "644"); // Simulate a backend returning a different initial mode; publication must correct it.
						if (competingTargetOnUpload) files.put(ABSOLUTE, "competing".getBytes(StandardCharsets.UTF_8));
						reply(exchange, 201, "");
					} else if ("OPEN".equals(op)) {
						if (stalledBody) {
							exchange.sendResponseHeaders(200, 0);
							exchange.getResponseBody().write(1);
							exchange.getResponseBody().flush();
							Thread.sleep(2000);
						} else if (files.containsKey(path)) reply(exchange, 200, files.get(path));
						else reply(exchange, 404, "missing");
					} else reply(exchange, 400, "bad operation");
					return;
				}
				switch (op) {
					case "GETFILESTATUS" -> {
						if (files.containsKey(path)) json(exchange, Map.of("FileStatus", Map.of("type", "FILE", "length", files.get(path).length,
							"permission", permissions.getOrDefault(path, "600"))));
						else if (directories.contains(path)) json(exchange, Map.of("FileStatus", Map.of("type", "DIRECTORY", "length", 0)));
						else reply(exchange, 404, "missing");
					}
					case "MKDIRS" -> { directories.add(path); json(exchange, Map.of("boolean", true)); }
					case "SETPERMISSION" -> { permissions.put(path, query.get("permission")); reply(exchange, 200, ""); }
					case "CREATE", "OPEN" -> {
						if ("CREATE".equals(op)) handshakeBodies.add(exchange.getRequestBody().readAllBytes());
						if ("OPEN".equals(op) && !files.containsKey(path)) { reply(exchange, 404, "missing"); return; }
						String target = redirectOverride != null ? redirectOverride : dataNodeOrigin() + "/webhdfs/v1" + path + "?" + exchange.getRequestURI().getRawQuery();
						exchange.getResponseHeaders().add("Location", target);
						reply(exchange, 307, "");
					}
					case "RENAME" -> {
						String destination = query.get("destination");
						if (renameFails || files.containsKey(destination) || !files.containsKey(path)) json(exchange, Map.of("boolean", false));
						else { files.put(destination, files.remove(path)); permissions.put(destination, permissions.remove(path)); json(exchange, Map.of("boolean", true)); }
					}
					case "DELETE" -> { files.remove(path); json(exchange, Map.of("boolean", true)); }
					case "LISTSTATUS" -> {
						if (largeListing) { reply(exchange, 200, new byte[WebHdfsClient.MAX_BYTES + 1]); return; }
						if (!directories.contains(path)) { reply(exchange, 404, "missing"); return; }
						List<Map<String, Object>> entries = new ArrayList<>();
						files.forEach((file, bytes) -> {
							String prefix = path + "/";
							if (file.startsWith(prefix) && !file.substring(prefix.length()).contains("/")) {
								entries.add(Map.of("pathSuffix", file.substring(prefix.length()), "type", "FILE", "modificationTime", 12345));
							}
						});
						json(exchange, Map.of("FileStatuses", Map.of("FileStatus", entries)));
					}
					default -> reply(exchange, 400, "bad operation");
				}
			} catch (IOException | InterruptedException ignored) {
				// Client cancellation is expected in bounded-body/timeout tests.
			} finally { exchange.close(); }
		}

		private static Map<String, String> query(String raw) {
			Map<String, String> query = new HashMap<>();
			for (String part : raw.split("&")) {
				String[] pair = part.split("=", 2);
				query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
			}
			return query;
		}
		void json(HttpExchange exchange, Object value) throws IOException { reply(exchange, 200, mapper.writeValueAsBytes(value)); }
		void reply(HttpExchange exchange, int status, String text) throws IOException { reply(exchange, status, text.getBytes(StandardCharsets.UTF_8)); }
		void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
			exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
			if (body.length > 0) exchange.getResponseBody().write(body);
		}
		@Override public void close() {
			client.close();
			nameNode.stop(0);
			dataNode.stop(0);
			executor.shutdownNow();
		}
	}
}
