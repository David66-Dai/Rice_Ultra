package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A shared store double survives service replacement; no network services are called. */
class AiAnalysisServiceTests {
	private static final AiAnalysisRequest REQUEST = new AiAnalysisRequest("S01", LocalDate.of(2020, 1, 1), 7, "unknown");
	private static final DifyWorkflowClient.Output OUTPUT = new DifyWorkflowClient.Output("w", "s", "r", "summary", "run-test");

	@Test void deduplicatesActiveRequestsRejectsDifferentWorkAtSameStationAndHidesTasks() throws Exception {
		var f = new Fixture();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		when(f.client.run(any(), anyString())).thenAnswer(call -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return OUTPUT;
		});
		var service = f.service();
		try {
			AiAnalysisJob first = service.submit("alice", REQUEST);
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(service.submit("alice", REQUEST).id()).isEqualTo(first.id());
			assertThat(service.submit("alice", new AiAnalysisRequest("S01", REQUEST.date(), 7, null)).id()).isEqualTo(first.id());
			assertStatus(() -> service.submit("alice", new AiAnalysisRequest("S01", REQUEST.date(), 14, "unknown")), 409);
			assertStatus(() -> service.submit("bob", REQUEST), 409);
			assertStatus(() -> service.get("bob", first.id()), 404);
			release.countDown();
			AiAnalysisJob done = awaitDone(service, first.id());
			assertThat(done.status()).isEqualTo("succeeded");
			assertThat(done.result().summary()).isEqualTo("summary");
			assertThat(done.archiveId()).isNotBlank();
			assertThat(done.generatedAt()).isNotNull();
			assertThat(done.expiresAt()).isNotNull();
			verify(f.client, times(1)).run(any(), anyString());
			verify(f.store, times(1)).save(eq("alice"), eq(REQUEST), any(), any());
		} finally { release.countDown(); service.close(); }
	}

	@Test void completedReadsUsePersistedBodyAndCannotFallBackToAnInMemoryResult() throws Exception {
		var f = new Fixture();
		var service = f.service();
		try {
			AiAnalysisJob submitted = service.submit("alice", REQUEST);
			AiAnalysisJob done = awaitDone(service, submitted.id());
			f.replaceSummary(done.archiveId(), "body reread from persistent store");
			AiAnalysisJob reread = service.get("alice", submitted.id());
			assertThat(reread.id()).isEqualTo(submitted.id());
			assertThat(reread.archiveId()).isEqualTo(done.archiveId());
			assertThat(reread.result().summary()).isEqualTo("body reread from persistent store");
			f.archives.remove(done.archiveId());
			assertStatus(() -> service.get("alice", submitted.id()), 404);
			verify(f.store, atLeast(2)).read(eq("alice"), eq(done.archiveId()), any());
			verify(f.client, times(1)).run(any(), anyString());
		} finally { service.close(); }
	}

	@Test void newServiceReadsExpiredArchivesAndListsOnlyOwnedMetadataWithoutRerunningModel() throws Exception {
		var f = new Fixture();
		AiAnalysisJob done;
		var first = f.service();
		try { done = awaitDone(first, first.submit("alice", REQUEST).id()); }
		finally { first.close(); }
		f.clock.now = done.expiresAt().plusSeconds(1);
		var restarted = f.service();
		try {
			AiAnalysisJob restored = restarted.get("alice", done.archiveId());
			assertThat(restored.status()).isEqualTo("succeeded");
			assertThat(restored.expired()).isTrue();
			assertThat(restored.result().summary()).isEqualTo("summary");
			LocalDate generatedDate = done.generatedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
			List<AiAnalysisJob> history = restarted.list("alice", "S01", generatedDate);
			assertThat(history).hasSize(1);
			assertThat(history.getFirst().result()).isNull();
			assertThat(history.getFirst().expired()).isTrue();
			assertThat(history.getFirst().archiveId()).isEqualTo(done.archiveId());
			assertThat(restarted.list("alice", "S01", generatedDate.minusDays(1))).isEmpty();
			assertThat(restarted.list("bob", "S01", generatedDate)).isEmpty();
			assertStatus(() -> restarted.get("bob", done.archiveId()), 404);
			verify(f.client, times(1)).run(any(), anyString());
		} finally { restarted.close(); }
		assertThat(f.archives).containsKey(done.archiveId());
	}

	@Test void saveFailureCannotBecomeSuccessfulOrExposeAnUnsavedBody() throws Exception {
		var f = new Fixture();
		doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private upstream diagnostic"))
			.when(f.store).save(anyString(), any(), any(), any());
		var service = f.service();
		try {
			AiAnalysisJob done = awaitDone(service, service.submit("alice", REQUEST).id());
			assertThat(done.status()).isEqualTo("failed");
			assertThat(done.result()).isNull();
			assertThat(done.archiveId()).isNull();
			assertThat(done.error()).contains("HDFS 保存失败").doesNotContain("private upstream diagnostic");
			assertThat(f.archives).isEmpty();
			verify(f.client, times(1)).run(any(), anyString());
			verify(f.store, never()).read(anyString(), anyString(), any());
		} finally { service.close(); }
	}

	@Test void unavailableOrConflictingPreflightDoesNotReadEvidenceOrCallModel() {
		var f = new Fixture();
		doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HDFS 暂不可用"))
			.when(f.store).prepare(anyString(), any());
		var service = f.service();
		try {
			assertStatus(() -> service.submit("alice", REQUEST), 503);
			doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "本分钟已有报告"))
				.when(f.store).prepare(anyString(), any());
			assertStatus(() -> service.submit("alice", REQUEST), 409);
			verifyNoInteractions(f.evidence);
			verify(f.client, never()).run(any(), any());
			verify(f.store, never()).save(anyString(), any(), any(), any());
		} finally { service.close(); }
	}

	@Test void configurationRequiresBothWorkflowAndArchive() {
		var f = new Fixture();
		when(f.store.configured()).thenReturn(false);
		var service = f.service();
		try {
			assertThat(service.configured()).isFalse();
			assertStatus(() -> service.submit("alice", REQUEST), 503);
			verify(f.store, never()).prepare(anyString(), any());
			verifyNoInteractions(f.evidence);
			verify(f.client, never()).run(any(), any());
		} finally { service.close(); }
	}

	@Test void validatesBeforeReadingHiveAndReturnsSanitizedFailure() throws Exception {
		var f = new Fixture();
		when(f.evidence.evidence(anyString(), any(), anyInt(), any())).thenThrow(new IllegalStateException("secret_password"));
		var service = f.service();
		try {
			assertStatus(() -> service.submit("alice", new AiAnalysisRequest("S11", REQUEST.date(), 7, "unknown")), 400);
			assertStatus(() -> service.submit("alice", new AiAnalysisRequest("S01", REQUEST.date(), 365, "unknown")), 400);
			verifyNoInteractions(f.evidence);
			AiAnalysisJob done = awaitDone(service, service.submit("alice", REQUEST).id());
			assertThat(done.status()).isEqualTo("failed");
			assertThat(done.error()).doesNotContain("secret_password");
			assertThat(done.result()).isNull();
			verify(f.client, never()).run(any(), any());
			verify(f.store, never()).save(anyString(), any(), any(), any());
		} finally { service.close(); }
	}

	@Test void closeInterruptsEvidenceAndRejectsFurtherSubmissionsBeforeCallingDify() throws Exception {
		var f = new Fixture();
		CountDownLatch entered = new CountDownLatch(1), wait = new CountDownLatch(1);
		when(f.evidence.evidence(anyString(), any(), anyInt(), any())).thenAnswer(call -> {
			entered.countDown();
			try { wait.await(5, TimeUnit.SECONDS); }
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
			return null;
		});
		var service = f.service();
		try {
			AiAnalysisJob submitted = service.submit("alice", REQUEST);
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			service.close();
			AiAnalysisJob stopped = service.get("alice", submitted.id());
			assertThat(stopped.status()).isEqualTo("failed");
			assertThat(stopped.result()).isNull();
			assertStatus(() -> service.submit("alice", REQUEST), 503);
			verify(f.client, never()).run(any(), any());
			verify(f.store, never()).save(anyString(), any(), any(), any());
		} finally { wait.countDown(); service.close(); }
	}

	private static AiAnalysisJob awaitDone(AiAnalysisService service, String id) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			AiAnalysisJob job = service.get("alice", id);
			if (job.completedAt() != null) return job;
			Thread.sleep(5);
		}
		throw new AssertionError("Job did not terminate");
	}
	private static void assertStatus(Runnable action, int status) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
			ex -> assertThat(ex.getStatusCode().value()).isEqualTo(status));
	}
	private record Stored(String owner, AiAnalysisJob job) {}
	private static final class MutableClock extends Clock {
		volatile Instant now = Instant.parse("2026-09-11T03:04:05Z");
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
		@Override public Instant instant() { return now; }
	}
	private static final class Fixture {
		final AiEvidenceService evidence = mock(AiEvidenceService.class);
		final DifyWorkflowClient client = mock(DifyWorkflowClient.class);
		final AiReportStore store = mock(AiReportStore.class);
		final MutableClock clock = new MutableClock();
		final Map<String, Stored> archives = new ConcurrentHashMap<>();
		Fixture() {
			when(client.configured()).thenReturn(true);
			when(store.configured()).thenReturn(true);
			when(client.run(any(), anyString())).thenReturn(OUTPUT);
			when(store.save(anyString(), any(), any(), any())).thenAnswer(call -> {
				String owner = call.getArgument(0);
				AiAnalysisRequest request = call.getArgument(1);
				AiAnalysisJob completed = call.getArgument(2);
				Instant generated = call.getArgument(3, Instant.class).truncatedTo(ChronoUnit.MINUTES);
				String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(generated.atZone(ZoneId.of("Asia/Shanghai")));
				String id = request.stationId() + "_" + stamp;
				AiAnalysisJob archived = new AiAnalysisJob(id, request.stationId(), request.date(), request.windowDays(), "succeeded",
					completed.createdAt(), completed.completedAt(), null, completed.result(), generated, generated.plusSeconds(3600), false,
					id, "/rice/output/point_" + Integer.parseInt(request.stationId().substring(1)) + "/output_" + stamp + ".json");
				if (archives.putIfAbsent(id, new Stored(owner, archived)) != null) throw new ResponseStatusException(HttpStatus.CONFLICT);
				return archived;
			});
			when(store.read(anyString(), anyString(), any())).thenAnswer(call -> {
				Stored value = archives.get(call.getArgument(1, String.class));
				if (value == null || !value.owner().equals(call.getArgument(0))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
				return fresh(value.job(), call.getArgument(2), false);
			});
			when(store.list(anyString(), anyString(), any(), any())).thenAnswer(call -> {
				String owner = call.getArgument(0), station = call.getArgument(1);
				LocalDate generatedDate = call.getArgument(2);
				Instant now = call.getArgument(3);
				return archives.values().stream().filter(value -> value.owner().equals(owner) && value.job().stationId().equals(station))
					.filter(value -> generatedDate == null || value.job().generatedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(generatedDate))
					.map(value -> fresh(value.job(), now, true)).sorted(Comparator.comparing(AiAnalysisJob::generatedAt).reversed()).toList();
			});
		}
		AiAnalysisService service() { return new AiAnalysisService(evidence, client, store, clock); }
		void replaceSummary(String id, String summary) {
			archives.compute(id, (key, value) -> {
				AiAnalysisJob job = value.job();
				AiAnalysisResult old = job.result();
				return new Stored(value.owner(), new AiAnalysisJob(job.id(), job.stationId(), job.date(), job.windowDays(), job.status(),
					job.createdAt(), job.completedAt(), job.error(), new AiAnalysisResult(old.evidence(), old.weatherAnalysis(), old.soilAnalysis(),
					old.riskAnalysis(), summary, old.workflowRunId()), job.generatedAt(), job.expiresAt(), job.expired(), job.archiveId(), job.archivePath()));
			});
		}
		private static AiAnalysisJob fresh(AiAnalysisJob job, Instant now, boolean metadataOnly) {
			return new AiAnalysisJob(job.id(), job.stationId(), job.date(), job.windowDays(), job.status(), job.createdAt(), job.completedAt(),
				job.error(), metadataOnly ? null : job.result(), job.generatedAt(), job.expiresAt(), now.isAfter(job.expiresAt()), job.archiveId(), job.archivePath());
		}
	}
}
