package com.smartrice.server.hdfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Bounded WebHDFS transport. No Hadoop runtime, automatic redirects or application retries. */
@Component
public class WebHdfsClient implements AutoCloseable {

	public static final int MAX_BYTES = 2 * 1024 * 1024;
	private static final String REPORT_PERMISSION = "766";
	private static final String DIRECTORY_PERMISSION = "755";
	private static final String PREFIX = "/webhdfs/v1";
	private final HdfsProperties properties;
	private final ObjectMapper mapper;
	private HttpClient client;

	public WebHdfsClient(HdfsProperties properties, ObjectMapper mapper) {
		this.properties = properties;
		this.mapper = mapper;
	}

	public boolean configured() {
		try { settings(); return true; }
		catch (RuntimeException ex) { return false; }
	}

	/** Returns a scoped HDFS path, never the HTTP endpoint or authentication query. */
	public String absolutePath(String relativePath) {
		return resolve(settings(), relativePath, false);
	}

	public void writeAtomic(String relativePath, byte[] content) {
		Settings settings = settings();
		String target = resolve(settings, relativePath, false);
		if (content == null || content.length > MAX_BYTES) throw unavailable();
		if (status(settings, target).isPresent()) throw conflict();
		String parent = target.substring(0, target.lastIndexOf('/'));
		booleanResult(send(settings, "PUT", operation(settings, parent, "MKDIRS", Map.of("permission", DIRECTORY_PERMISSION)), null), 200);
		setPermission(settings, parent, DIRECTORY_PERMISSION);
		String temporary = parent + "/.upload-" + UUID.randomUUID() + ".tmp";
		boolean renamed = false;
		try {
			URI create = operation(settings, temporary, "CREATE", Map.of("overwrite", "false", "permission", REPORT_PERMISSION));
			HttpResponse<byte[]> handshake = send(settings, "PUT", create, null);
			if (handshake.statusCode() != 307) throw unavailable();
			URI dataNode = redirect(settings, handshake, temporary, "CREATE");
			if (send(settings, "PUT", dataNode, content.clone()).statusCode() != 201) throw unavailable();
			// Apply the requested report mode before publishing, then verify it on the final file.
			setPermission(settings, temporary, REPORT_PERMISSION);
			// A competing completed report must remain intact, even after the upload was prepared.
			if (status(settings, target).isPresent()) throw conflict();
			HttpResponse<byte[]> rename = send(settings, "PUT", operation(settings, temporary, "RENAME", Map.of("destination", target)), null);
			if (rename.statusCode() == 200 && isTrue(rename)) {
				renamed = true; // Successful rename consumes the temporary path; no second persisted file remains.
			} else {
				if (status(settings, target).isPresent()) throw conflict();
				throw unavailable();
			}
			JsonNode finalStatus = status(settings, target).orElseThrow(WebHdfsClient::unavailable);
			if (!"FILE".equals(finalStatus.path("type").asText()) || finalStatus.path("length").asLong(-1) != content.length
					|| !REPORT_PERMISSION.equals(finalStatus.path("permission").asText())) {
				throw unavailable();
			}
		} finally {
			if (!renamed) {
				// Only this invocation's random temporary file can be deleted, always non-recursively.
				try { send(settings, "DELETE", operation(settings, temporary, "DELETE", Map.of("recursive", "false")), null); }
				catch (RuntimeException ignored) { /* Preserve the original failure; hidden orphan is never listed as a report. */ }
			}
		}
	}

	public Optional<byte[]> read(String relativePath) {
		Settings settings = settings();
		String path = resolve(settings, relativePath, false);
		HttpResponse<byte[]> response = send(settings, "GET", operation(settings, path, "OPEN", Map.of()), null);
		if (response.statusCode() == 307) response = send(settings, "GET", redirect(settings, response, path, "OPEN"), null);
		if (response.statusCode() == 404) return Optional.empty();
		if (response.statusCode() != 200) throw unavailable();
		return Optional.of(response.body());
	}

	public List<FileEntry> list(String relativeDirectory) {
		Settings settings = settings();
		String path = resolve(settings, relativeDirectory, true);
		HttpResponse<byte[]> response = send(settings, "GET", operation(settings, path, "LISTSTATUS", Map.of()), null);
		if (response.statusCode() == 404) return List.of();
		if (response.statusCode() != 200) throw unavailable();
		JsonNode entries = json(response).path("FileStatuses").path("FileStatus");
		if (!entries.isArray()) throw unavailable();
		List<FileEntry> result = new ArrayList<>();
		for (JsonNode entry : entries) {
			String name = entry.path("pathSuffix").asText("");
			String type = entry.path("type").asText("");
			if (name.startsWith(".") || !safeSegment(name) || "SYMLINK".equals(type)) continue;
			if ((!"FILE".equals(type) && !"DIRECTORY".equals(type)) || !entry.path("modificationTime").canConvertToLong()) throw unavailable();
			result.add(new FileEntry(name, "DIRECTORY".equals(type), entry.path("modificationTime").longValue()));
		}
		return List.copyOf(result);
	}

	public record FileEntry(String name, boolean directory, long modificationTime) { }

	private Optional<JsonNode> status(Settings settings, String path) {
		HttpResponse<byte[]> response = send(settings, "GET", operation(settings, path, "GETFILESTATUS", Map.of()), null);
		if (response.statusCode() == 404) return Optional.empty();
		if (response.statusCode() != 200) throw unavailable();
		JsonNode status = json(response).path("FileStatus");
		if (!status.isObject()) throw unavailable();
		return Optional.of(status);
	}

	private void booleanResult(HttpResponse<byte[]> response, int expectedStatus) {
		if (response.statusCode() != expectedStatus || !isTrue(response)) throw unavailable();
	}

	private void setPermission(Settings settings, String path, String permission) {
		if (send(settings, "PUT", operation(settings, path, "SETPERMISSION", Map.of("permission", permission)), null).statusCode() != 200)
			throw unavailable();
	}

	private boolean isTrue(HttpResponse<byte[]> response) {
		JsonNode result = json(response).path("boolean");
		return result.isBoolean() && result.booleanValue();
	}

	private JsonNode json(HttpResponse<byte[]> response) {
		try {
			JsonNode value = mapper.readTree(response.body());
			if (value == null) throw unavailable();
			return value;
		} catch (IOException | RuntimeException ex) { throw unavailable(); }
	}

	private Settings settings() {
		try {
			URI endpoint = URI.create(properties.getWebUrl());
			if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
					|| endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
					|| !(endpoint.getRawPath().isEmpty() || "/".equals(endpoint.getRawPath()))
					|| endpoint.getPort() == 0 || endpoint.getPort() > 65535) throw unavailable();
			String base = properties.getBasePath();
			if (base == null || !base.startsWith("/") || base.length() < 2) throw unavailable();
			if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
			validateRelative(base.substring(1), false);
			String user = properties.getUser();
			if (user == null || !user.matches("[A-Za-z0-9_.-]{1,128}")) throw unavailable();
			if (properties.getConnectTimeoutSeconds() <= 0 || properties.getConnectTimeoutSeconds() > 60
					|| properties.getTimeoutSeconds() <= 0 || properties.getTimeoutSeconds() > 300) throw unavailable();
			Map<String, String> overrides = new LinkedHashMap<>();
			for (Map.Entry<String, String> entry : properties.getDataNodeHostOverrides().entrySet()) {
				if (!safeHost(entry.getKey()) || !safeHost(entry.getValue())) throw unavailable();
				overrides.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
			}
			String origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
			return new Settings(URI.create(origin), base, user, properties.getConnectTimeoutSeconds(), properties.getTimeoutSeconds(), Map.copyOf(overrides));
		} catch (RuntimeException ex) { throw unavailable(); }
	}

	private static boolean safeHost(String host) {
		if (host == null || host.isBlank()) return false;
		try {
			URI uri = URI.create("http://" + host);
			return uri.getHost() != null && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawPath().isEmpty()
				&& uri.getRawQuery() == null && uri.getRawFragment() == null;
		} catch (RuntimeException ex) { return false; }
	}

	private static String resolve(Settings settings, String relative, boolean rootAllowed) {
		validateRelative(relative, rootAllowed);
		return relative.isEmpty() ? settings.basePath() : settings.basePath() + "/" + relative;
	}

	private static void validateRelative(String relative, boolean rootAllowed) {
		if (relative == null || relative.length() > 1024 || (relative.isEmpty() && !rootAllowed)) throw unavailable();
		if (relative.isEmpty()) return;
		for (String segment : relative.split("/", -1)) if (!safeSegment(segment)) throw unavailable();
	}

	private static boolean safeSegment(String segment) {
		return segment != null && segment.matches("[A-Za-z0-9_.-]{1,200}") && !segment.equals(".") && !segment.equals("..") && !segment.equals(".snapshot");
	}

	private URI operation(Settings settings, String path, String op, Map<String, String> arguments) {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("op", op);
		query.put("user.name", settings.user());
		query.putAll(arguments);
		return URI.create(settings.endpoint() + PREFIX + path + "?" + encode(query));
	}

	private URI redirect(Settings settings, HttpResponse<byte[]> response, String path, String expectedOp) {
		try {
			URI target = URI.create(response.headers().firstValue("Location").orElseThrow(WebHdfsClient::unavailable));
			if (!settings.endpoint().getScheme().equals(target.getScheme()) || target.getHost() == null
					|| target.getUserInfo() != null || target.getFragment() != null || target.getPort() == 0 || target.getPort() > 65535
					|| !(PREFIX + path).equals(target.getRawPath())) throw unavailable();
			Map<String, String> query = decode(target.getRawQuery());
			if (!expectedOp.equals(query.get("op")) || (query.containsKey("user.name") && !settings.user().equals(query.get("user.name"))))
				throw unavailable();
			if (query.containsKey("doas") || query.containsKey("delegation") || query.containsKey("destination")) throw unavailable();
			query.put("user.name", settings.user());
			if ("CREATE".equals(expectedOp)) {
				if (query.containsKey("overwrite") && !"false".equals(query.get("overwrite"))) throw unavailable();
				query.put("overwrite", "false");
				query.put("permission", REPORT_PERMISSION);
			}
			String host = settings.overrides().getOrDefault(target.getHost().toLowerCase(Locale.ROOT), target.getHost());
			if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
			String authority = host + (target.getPort() < 0 ? "" : ":" + target.getPort());
			return URI.create(target.getScheme() + "://" + authority + target.getRawPath() + "?" + encode(query));
		} catch (RuntimeException ex) { throw unavailable(); }
	}

	private static String encode(Map<String, String> query) {
		return query.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
			+ URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
	}

	private static Map<String, String> decode(String rawQuery) {
		if (rawQuery == null) throw unavailable();
		Map<String, String> result = new LinkedHashMap<>();
		for (String part : rawQuery.split("&")) {
			String[] pair = part.split("=", 2);
			if (pair.length != 2) throw unavailable();
			String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
			if (result.putIfAbsent(key, URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw unavailable();
		}
		return result;
	}

	private synchronized HttpClient client(Settings settings) {
		if (client == null) client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.connectTimeout()))
			.followRedirects(HttpClient.Redirect.NEVER).build();
		return client;
	}

	private HttpResponse<byte[]> send(Settings settings, String method, URI uri, byte[] content) {
		CompletableFuture<HttpResponse<byte[]>> pending = null;
		try {
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(settings.timeout()))
				.header("Accept", "application/json")
				.header("Content-Type", "application/octet-stream")
				.method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(content)).build();
			pending = client(settings).sendAsync(request, info -> new LimitedBodySubscriber(MAX_BYTES));
			// Wait for the complete bounded body, not just response headers (e.g. stalled chunked responses).
			return pending.get(settings.timeout(), TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			if (pending != null) pending.cancel(true);
			Thread.currentThread().interrupt();
			throw unavailable();
		} catch (Exception ex) {
			if (pending != null) pending.cancel(true);
			throw unavailable();
		}
	}

	@Override
	@PreDestroy
	public synchronized void close() {
		if (client != null) client.shutdownNow();
	}

	private static ResponseStatusException unavailable() {
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HDFS 报告存储暂不可用，请检查服务端配置或稍后重试。");
	}

	private static ResponseStatusException conflict() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "该时刻的报告已存在，请在下一分钟重新生成，已有报告不会被覆盖。");
	}

	private record Settings(URI endpoint, String basePath, String user, int connectTimeout, int timeout, Map<String, String> overrides) { }

	private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final int limit;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private Flow.Subscription subscription;
		LimitedBodySubscriber(int limit) { this.limit = limit; }
		@Override public CompletionStage<byte[]> getBody() { return body; }
		@Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
		@Override public void onNext(List<ByteBuffer> buffers) {
			for (ByteBuffer buffer : buffers) {
				if ((long) bytes.size() + buffer.remaining() > limit) {
					subscription.cancel();
					body.completeExceptionally(new IOException("Response exceeds storage limit"));
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}
		@Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
		@Override public void onComplete() { body.complete(bytes.toByteArray()); }
	}
}
