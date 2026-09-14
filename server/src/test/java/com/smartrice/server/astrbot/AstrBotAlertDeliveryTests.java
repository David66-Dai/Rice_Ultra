package com.smartrice.server.astrbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.realtime.DeviceActivityService;
import com.smartrice.server.realtime.PreventionControlProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * WeChat and QQ are alerted independently. A platform that is down must never silence the other
 * one, never invalidate a confirmation the reachable platform already received, and never cause
 * a re-send to a session that already got the alert.
 */
@SpringBootTest
@ActiveProfiles("dev")
class AstrBotAlertDeliveryTests {

	private static final String WECHAT = "wx:FriendMessage:offline-wechat-session";
	private static final String QQ = "qq_ricebot:FriendMessage:0F1E2D3C4B5A69788796A5B4C3D2E1F0";

	@Autowired ObjectMapper json;
	@Autowired AstrBotDiagnosisConfirmationRepository confirmations;
	@Autowired AstrBotDiagnosisConfirmationTargetRepository targets;

	private final PreventionControlProperties properties = new PreventionControlProperties();
	private final Map<String, AtomicInteger> requestsPerUmo = new ConcurrentHashMap<>();
	private final DeviceActivityService activity = mock(DeviceActivityService.class);
	private HttpClient http;
	private AstrBotAlertSender sender;

	@BeforeEach
	void setUp() {
		targets.deleteAll();
		confirmations.deleteAll();
		requestsPerUmo.clear();
		properties.setAstrBotAlertEnabled(true);
		properties.setAstrBotBaseUrl("http://127.0.0.1:6185");
		properties.setAstrBotApiKey("offline-alert-api-key");
		properties.setAlertUmos(List.of(WECHAT, QQ));
		http = mock(HttpClient.class);
		sender = new AstrBotAlertSender(properties, confirmations, targets, json, activity, http);
	}

	@AfterEach
	void tearDown() {
		sender.close();
	}

	@Test
	void bothSessionsReceiveTheWholeAlertWhenBothAreOnline() throws Exception {
		everySessionReachable();
		String id = pending();
		int blocks = AstrBotAlertSender.alertBlocks(alertText(id)).size();

		sender.sendAfterCommit(id);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
			confirmations.findById(id).orElseThrow().getDeliveryStatus()).isEqualTo("SENT"));
		// WeChat and QQ each received every block of the same alert -- not one instead of the other.
		assertThat(sent(WECHAT)).isEqualTo(blocks);
		assertThat(sent(QQ)).isEqualTo(blocks);
		assertThat(targets.findByKeyConfirmationId(id)).hasSize(2)
			.allSatisfy(target -> {
				assertThat(target.isDelivered()).isTrue();
				assertThat(target.getSentAt()).isNotNull();
				assertThat(target.getAttempts()).isEqualTo(1);
				assertThat(target.getLastError()).isNull();
			});
	}

	@Test
	void automaticDeviceFeedbackAlsoReachesBothSessions() throws Exception {
		everySessionReachable();

		sender.sendDeviceFeedback("S01", "pump", false, "自动关闭", "已到达设定时长，已执行自动关闭。");

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(sent(WECHAT)).isEqualTo(1);
			assertThat(sent(QQ)).isEqualTo(1);
		});
		// Nothing failed, so no big-screen fallback notification is raised.
		verify(activity, never()).publishPestDisease(anyString(), anyString());
	}

	@Test
	void deviceFeedbackStillReachesTheOnlineSessionWhenTheOtherIsDown() throws Exception {
		onlyThisSessionFails(WECHAT);

		sender.sendDeviceFeedback("S01", "lamp", false, "自动关闭", "已到达设定时长，已执行自动关闭。");

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(sent(QQ)).isEqualTo(1));
		assertThat(sent(WECHAT)).isEqualTo(1);
		// The operator watching the failed platform is told through the shared screen instead.
		verify(activity, timeout(5000)).publishPestDisease(eqStation(), contains("未送达"));
	}

	@Test
	void aFailingWeChatSessionStillLetsQqReceiveTheSameAlert() throws Exception {
		onlyThisSessionFails(WECHAT);
		String id = pending();

		sender.sendAfterCommit(id);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(target(QQ).map(AstrBotDiagnosisConfirmationTarget::getDeliveryStatus)).contains("SENT");
			assertThat(target(WECHAT).map(AstrBotDiagnosisConfirmationTarget::getDeliveryStatus)).contains("FAILED");
		});
		assertThat(target(QQ).orElseThrow().getSentAt()).isNotNull();
		assertThat(target(WECHAT).orElseThrow().getSentAt()).isNull();
		assertThat(target(WECHAT).orElseThrow().getLastError()).isNotBlank();
		// The summary says partial, and the confirmation stays open for the session that got it.
		AstrBotDiagnosisConfirmation row = confirmations.findById(id).orElseThrow();
		assertThat(row.getDeliveryStatus()).isEqualTo("PARTIAL");
		assertThat(row.getStatus()).isEqualTo("PENDING");
		assertThat(row.getSentAt()).isNotNull();
	}

	@Test
	void retriesTargetOnlyTheFailedSessionAndNeverResendToADeliveredOne() throws Exception {
		onlyThisSessionFails(WECHAT);
		String id = pending();

		sender.sendAfterCommit(id);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
			assertThat(target(QQ).map(AstrBotDiagnosisConfirmationTarget::isDelivered)).contains(true));
		int qqAfterFirstPass = sent(QQ);
		assertThat(qqAfterFirstPass).isEqualTo(AstrBotAlertSender.alertBlocks(alertText(id)).size());
		int weChatAfterFirstPass = sent(WECHAT);

		// A second pass -- what restart recovery does -- retries WeChat and leaves QQ alone.
		sender.sendAfterCommit(id);
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(sent(WECHAT)).isGreaterThan(weChatAfterFirstPass));

		assertThat(sent(QQ)).isEqualTo(qqAfterFirstPass);
		assertThat(target(QQ).orElseThrow().getAttempts()).isEqualTo(1);
		assertThat(target(WECHAT).orElseThrow().getAttempts()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void everySessionDeliveredIsSummarizedAsSentAndATotalOutageAsFailed() throws Exception {
		onlyThisSessionFails("no-such-session");
		String delivered = pending();
		sender.sendAfterCommit(delivered);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
			confirmations.findById(delivered).orElseThrow().getDeliveryStatus()).isEqualTo("SENT"));
		assertThat(confirmations.findById(delivered).orElseThrow().getLastError()).isNull();

		failEverySession();
		String lost = pending();
		sender.sendAfterCommit(lost);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
			confirmations.findById(lost).orElseThrow().getDeliveryStatus()).isEqualTo("FAILED"));
		assertThat(targets.findByKeyConfirmationId(lost))
			.hasSize(2).allSatisfy(target -> assertThat(target.isDelivered()).isFalse());
		assertThat(confirmations.findById(lost).orElseThrow().getSentAt()).isNull();
	}

	@Test
	void brokenAlertConfigurationSendsNothingAndLeavesTheConfirmationUndelivered() throws Exception {
		onlyThisSessionFails("no-such-session");
		properties.setAstrBotApiKey("");
		String id = pending();

		sender.sendAfterCommit(id);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
			confirmations.findById(id).orElseThrow().getDeliveryStatus()).isEqualTo("FAILED"));
		assertThat(confirmations.findById(id).orElseThrow().getSentAt()).isNull();
		assertThat(targets.findByKeyConfirmationId(id)).isEmpty();
		assertThat(requestsPerUmo).isEmpty();
	}

	private int sent(String umo) {
		AtomicInteger counter = requestsPerUmo.get(umo);
		return counter == null ? 0 : counter.get();
	}

	private Optional<AstrBotDiagnosisConfirmationTarget> target(String umo) {
		return targets.findAll().stream().filter(row -> umo.equals(row.getUmo())).findFirst();
	}

	private String pending() {
		String id = UUID.randomUUID().toString();
		Instant now = Instant.now();
		confirmations.saveAndFlush(new AstrBotDiagnosisConfirmation(id, id.hashCode(), "S01", "pump", 3L,
			alertText(id), now, now.plus(Duration.ofMinutes(10))));
		return id;
	}

	private static String alertText(String id) {
		return String.join("\n",
			"🔴 Rice Ultra 病虫害防治确认",
			"站点：S01",
			"识别到细菌性叶枯病，置信度 98.0%",
			"拟开启：智能喷药",
			"确认编号：" + id,
			"请在 10 分钟内由收到告警的授权用户发送：/agri_confirm_alert " + id,
			"确认时后端会再次核验设备版本与安全条件；未确认不会开启设备。");
	}

	private static String eqStation() {
		return org.mockito.ArgumentMatchers.eq("S01");
	}

	private static String contains(String fragment) {
		return org.mockito.ArgumentMatchers.contains(fragment);
	}

	/** Both platforms online: every configured session accepts the message. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void everySessionReachable() throws Exception {
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
			requestsPerUmo.computeIfAbsent(umoOf(invocation.getArgument(0)), key -> new AtomicInteger())
				.incrementAndGet();
			return okResponse();
		});
	}

	/** Every session is reachable except the named one, which always refuses the connection. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void onlyThisSessionFails(String failing) throws Exception {
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
			String umo = umoOf(invocation.getArgument(0));
			requestsPerUmo.computeIfAbsent(umo, key -> new AtomicInteger()).incrementAndGet();
			if (failing.equals(umo)) throw new IOException("offline: " + umo + " is unreachable");
			return okResponse();
		});
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void failEverySession() throws Exception {
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
			requestsPerUmo.computeIfAbsent(umoOf(invocation.getArgument(0)), key -> new AtomicInteger())
				.incrementAndGet();
			throw new IOException("offline: every session is unreachable");
		});
	}

	private String umoOf(HttpRequest request) throws Exception {
		CompletableFuture<String> body = new CompletableFuture<>();
		StringBuilder text = new StringBuilder();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
			@Override public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}
			@Override public void onNext(ByteBuffer item) {
				text.append(StandardCharsets.UTF_8.decode(item));
			}
			@Override public void onError(Throwable throwable) {
				body.completeExceptionally(throwable);
			}
			@Override public void onComplete() {
				body.complete(text.toString());
			}
		});
		return json.readTree(body.get(5, TimeUnit.SECONDS)).path("umo").asText();
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<String> okResponse() {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn("{\"status\":\"ok\"}");
		return response;
	}
}
