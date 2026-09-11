package com.smartrice.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.hdfs.WebHdfsClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Each successful generation has exactly one immutable JSON file, located by its Beijing timestamp. */
@Repository
public class AiReportStore {
	static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd_HHmm").withResolverStyle(ResolverStyle.STRICT);
	private static final Pattern FILE = Pattern.compile("^output_(\\d{8}_\\d{4})\\.json$");
	private static final Pattern ID = Pattern.compile("^(S(?:0[1-9]|10))_(\\d{8}_\\d{4})$");
	private final WebHdfsClient hdfs;
	private final ObjectMapper json;
	public AiReportStore(WebHdfsClient hdfs, ObjectMapper json) { this.hdfs = hdfs; this.json = json; }
	public boolean configured() { return hdfs.configured(); }
	public record Stored(int schemaVersion, String ownerHash, AiAnalysisRequest request, AiAnalysisJob job) {}
	private record Location(String station, String stamp, Instant generatedAt) {
		String id() { return station + "_" + stamp; }
		String relativePath() { return directory(station) + "/output_" + stamp + ".json"; }
	}

	/** Check availability and the minute collision before making a billable model request. */
	public void prepare(String station, Instant now) {
		validateStation(station);
		hdfs.list(directory(station));
		Location location = location(station, now);
		if (hdfs.read(location.relativePath()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,
			"该站点本分钟已有报告，请查看已保存报告或在下一分钟重新生成");
	}
	public AiAnalysisJob save(String owner, AiAnalysisRequest request, AiAnalysisJob completed, Instant generated) {
		Location location = location(request.stationId(), generated);
		AiAnalysisJob archived = new AiAnalysisJob(location.id(), request.stationId(), request.date(), request.windowDays(),
			"succeeded", completed.createdAt(), completed.completedAt(), null, completed.result(),
			location.generatedAt(), location.generatedAt().plus(1, ChronoUnit.HOURS), false, location.id(),
			hdfs.absolutePath(location.relativePath()));
		if (archived.result() == null) throw invalid();
		byte[] content;
		try { content = json.writeValueAsBytes(new Stored(1, ownerHash(owner), request, archived)); }
		catch (Exception ex) { throw invalid(); }
		// CREATE overwrite=false + completed temporary upload + RENAME: no partly written reports are exposed.
		hdfs.writeAtomic(location.relativePath(), content);
		return archived;
	}
	public AiAnalysisJob read(String owner, String id, Instant now) {
		Location location = parse(id);
		Stored stored = readFile(location).orElseThrow(AiReportStore::missing);
		if (!stored.ownerHash().equals(ownerHash(owner))) throw missing();
		return fresh(stored.job(), now, false);
	}
	public List<AiAnalysisJob> list(String owner, String station, LocalDate generatedDate, Instant now) {
		validateStation(station);
		String day = generatedDate == null ? null : generatedDate.format(DateTimeFormatter.BASIC_ISO_DATE);
		List<String> names = hdfs.list(directory(station)).stream().filter(entry -> !entry.directory())
			.map(WebHdfsClient.FileEntry::name).filter(name -> FILE.matcher(name).matches())
			.filter(name -> day == null || name.startsWith("output_" + day + "_"))
			.sorted(java.util.Comparator.reverseOrder()).toList();
		List<AiAnalysisJob> result = new ArrayList<>();
		String identity = ownerHash(owner);
		for (String name : names) {
			Location location = parse(station + "_" + name.substring(7, name.length() - 5));
			Optional<Stored> stored = readFile(location);
			if (stored.isPresent() && identity.equals(stored.get().ownerHash())) result.add(fresh(stored.get().job(), now, true));
			if (result.size() == 20) break;
		}
		return List.copyOf(result);
	}
	private Optional<Stored> readFile(Location location) {
		return hdfs.read(location.relativePath()).map(bytes -> {
			try {
				Stored stored = json.readValue(bytes, Stored.class);
				if (stored.schemaVersion() != 1 || stored.ownerHash() == null || stored.request() == null || stored.job() == null) throw invalid();
				AiAnalysisJob job = stored.job();
				if (!location.id().equals(job.id()) || !location.station().equals(job.stationId()) || !"succeeded".equals(job.status())
					|| job.result() == null || job.generatedAt() == null || !location.generatedAt().equals(job.generatedAt())
					|| !stored.request().date().equals(job.date()) || !stored.request().stationId().equals(job.stationId())) throw invalid();
				return stored;
			} catch (ResponseStatusException ex) { throw ex; }
			catch (Exception ex) { throw invalid(); }
		});
	}
	static AiAnalysisJob fresh(AiAnalysisJob job, Instant now, boolean metadataOnly) {
		Instant expires = job.generatedAt() == null ? null : job.generatedAt().plus(1, ChronoUnit.HOURS);
		return new AiAnalysisJob(job.id(), job.stationId(), job.date(), job.windowDays(), job.status(), job.createdAt(),
			job.completedAt(), job.error(), metadataOnly ? null : job.result(), job.generatedAt(), expires,
			expires != null && now.isAfter(expires), job.archiveId(), job.archivePath());
	}
	private static Location location(String station, Instant time) {
		String stamp = STAMP.format(time.atZone(ZONE));
		return parse(station + "_" + stamp);
	}
	private static Location parse(String id) {
		var matcher = ID.matcher(id == null ? "" : id);
		if (!matcher.matches()) throw missing();
		try { return new Location(matcher.group(1), matcher.group(2), LocalDateTime.parse(matcher.group(2), STAMP).atZone(ZONE).toInstant()); }
		catch (RuntimeException ex) { throw missing(); }
	}
	private static String directory(String station) { return "point_" + Integer.parseInt(station.substring(1)); }
	static void validateStation(String station) {
		if (station == null || !station.matches("S(?:0[1-9]|10)")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站点必须为 S01-S10");
	}
	private static String ownerHash(String owner) {
		if (owner == null || owner.isBlank()) throw missing();
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
	}
	private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前账号的分析报告"); }
	private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HDFS 报告格式无效或与文件名不一致"); }
}
