package com.smartrice.server.ai;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Only lightweight task status lives in memory. All successful report bodies are read from HDFS. */
@Service
public class AiAnalysisService {
	private final AiEvidenceService evidence;
	private final DifyWorkflowClient dify;
	private final AiReportStore reports;
	private final Clock clock;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore capacity = new Semaphore(2);
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private volatile boolean closing;
	private static final long RETENTION_SECONDS = 1800;
	private static final int MAX_RESULTS = 100;
	private record Entry(String owner, AiAnalysisRequest request, AiAnalysisJob job) {}

	@Autowired
	public AiAnalysisService(AiEvidenceService evidence, DifyWorkflowClient dify, AiReportStore reports) {
		this(evidence, dify, reports, Clock.systemUTC());
	}
	AiAnalysisService(AiEvidenceService evidence, DifyWorkflowClient dify, AiReportStore reports, Clock clock) {
		this.evidence = evidence;
		this.dify = dify;
		this.reports = reports;
		this.clock = clock;
	}
	public boolean configured() { return dify.configured() && reports.configured(); }

	public synchronized AiAnalysisJob submit(String owner, AiAnalysisRequest request) {
		if (request != null && request.growthStage() == null) request = new AiAnalysisRequest(request.stationId(), request.date(), request.windowDays(), "unknown");
		validate(request);
		if (closing) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务正在关闭，请稍后重试");
		if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 工作流或 HDFS 归档尚未配置，请联系管理员完成配置");
		prune();
		for (Entry entry : entries.values()) {
			if (entry.owner.equals(owner) && entry.request.equals(request) && active(entry.job)) return entry.job;
			if (entry.request.stationId().equals(request.stationId()) && active(entry.job)) throw new ResponseStatusException(HttpStatus.CONFLICT,
				"该站点已有分析正在进行，请等待报告保存后再生成");
		}
		reports.prepare(request.stationId(), clock.instant());
		if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已有分析正在进行，请稍后再试");
		makeRoom();
		String id = UUID.randomUUID().toString();
		AiAnalysisJob job = new AiAnalysisJob(id, request.stationId(), request.date(), request.windowDays(),
			"queued", clock.instant(), null, null, null);
		entries.put(id, new Entry(owner, request, job));
		AiAnalysisRequest submittedRequest = request;
		try { executor.execute(() -> execute(id, owner, submittedRequest)); }
		catch (RejectedExecutionException ex) {
			entries.remove(id); capacity.release();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务正在关闭，请稍后重试");
		}
		return job;
	}
	public AiAnalysisJob get(String owner, String id) {
		Entry entry;
		synchronized (this) { prune(); entry = entries.get(id); }
		if (entry == null) return reports.read(owner, id, clock.instant());
		if (!entry.owner.equals(owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前账号的分析任务");
		if (entry.job.archiveId() == null) return entry.job;
		AiAnalysisJob stored = reports.read(owner, entry.job.archiveId(), clock.instant());
		return new AiAnalysisJob(id, stored.stationId(), stored.date(), stored.windowDays(), stored.status(), stored.createdAt(),
			stored.completedAt(), stored.error(), stored.result(), stored.generatedAt(), stored.expiresAt(), stored.expired(), stored.archiveId(), stored.archivePath());
	}
	public List<AiAnalysisJob> list(String owner, String station, LocalDate generatedDate) {
		return reports.list(owner, station, generatedDate, clock.instant());
	}
	private void execute(String id, String owner, AiAnalysisRequest request) {
		boolean generated = false;
		try {
			update(id, "running", null, null);
			AiEvidence source = evidence.evidence(request.stationId(), request.date(), request.windowDays(), request.growthStage());
			if (closing || Thread.currentThread().isInterrupted())
				throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 分析已中断，请稍后重试");
			// Opaque run identity avoids sending login names or JWT claims to the model platform.
			DifyWorkflowClient.Output output = dify.run(source, "rice-ultra-" + id);
			generated = true;
			AiAnalysisResult result = new AiAnalysisResult(source, output.weatherAnalysis(), output.soilAnalysis(),
				output.riskAnalysis(), output.summary(), output.workflowRunId());
			Instant finished = clock.instant();
			AiAnalysisJob task;
			synchronized (this) { task = entries.get(id).job; }
			AiAnalysisJob archived = reports.save(owner, request, new AiAnalysisJob(id, task.stationId(), task.date(), task.windowDays(),
				"succeeded", task.createdAt(), finished, null, result), finished);
			synchronized (this) {
				// Retain a locator and status only. No evidence or report text is retained by this map.
				entries.put(id, new Entry(owner, request, new AiAnalysisJob(id, archived.stationId(), archived.date(), archived.windowDays(),
					"succeeded", archived.createdAt(), archived.completedAt(), null, null, archived.generatedAt(), archived.expiresAt(), false,
					archived.archiveId(), archived.archivePath())));
			}
		} catch (ResponseStatusException ex) {
			update(id, "failed", generated ? "报告已生成但 HDFS 保存失败，本次未标记成功；请检查归档服务后重新生成"
				: ex.getReason() == null ? "AI 分析失败，请稍后重试" : ex.getReason(), null);
		} catch (RuntimeException ex) {
			// Neither upstream payloads nor exception causes are returned to the browser.
			update(id, "failed", "AI 分析未完成，请检查服务端与 Dify 运行状态", null);
		} finally { capacity.release(); }
	}
	private synchronized void update(String id, String status, String error, AiAnalysisResult result) {
		Entry entry = entries.get(id);
		if (entry == null) return;
		AiAnalysisJob old = entry.job;
		entries.put(id, new Entry(entry.owner, entry.request, new AiAnalysisJob(id, old.stationId(), old.date(), old.windowDays(),
			status, old.createdAt(), "running".equals(status) ? null : clock.instant(), error, null)));
	}
	private void prune() {
		Instant cutoff = clock.instant().minusSeconds(RETENTION_SECONDS);
		entries.values().removeIf(entry -> !active(entry.job) && entry.job.completedAt().isBefore(cutoff));
	}
	private void makeRoom() {
		while (entries.size() >= MAX_RESULTS) {
			String candidate = entries.entrySet().stream().filter(entry -> !active(entry.getValue().job))
				.map(Map.Entry::getKey).findFirst().orElse(null);
			if (candidate == null) break;
			entries.remove(candidate);
		}
	}
	private static boolean active(AiAnalysisJob job) { return "queued".equals(job.status()) || "running".equals(job.status()); }
	private static void validate(AiAnalysisRequest r) {
		if (r == null || r.stationId() == null || !r.stationId().matches("S(?:0[1-9]|10)")
			|| r.date() == null || r.date().getYear() < 1 || r.date().getYear() > 9999
			|| r.date().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))
			|| !Set.of(7, 14, 30).contains(r.windowDays())
			|| (r.growthStage() != null && !Set.of("unknown", "seedling", "tillering", "jointing", "booting", "heading", "filling", "mature").contains(r.growthStage())))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效站点、日期、分析窗口和生育期");
	}
	@PreDestroy
	public void close() {
		synchronized (this) { closing = true; }
		executor.shutdownNow();
		try { executor.awaitTermination(50, TimeUnit.SECONDS); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
	}
}
