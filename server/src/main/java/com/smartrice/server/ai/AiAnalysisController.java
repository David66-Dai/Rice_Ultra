package com.smartrice.server.ai;

import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {
	private final AiAnalysisService analyses;
	private final AiEvidenceService evidence;
	public AiAnalysisController(AiAnalysisService analyses, AiEvidenceService evidence) {
		this.analyses = analyses; this.evidence = evidence;
	}
	@GetMapping("/status")
	public Map<String, Boolean> status() { return Map.of("configured", analyses.configured()); }
	@GetMapping("/evidence")
	public AiEvidence evidence(@RequestParam String stationId,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		@RequestParam(defaultValue = "7") int windowDays, @RequestParam(defaultValue = "unknown") String growthStage) {
		return evidence.evidence(stationId, date, windowDays, growthStage);
	}
	@PostMapping("/analyses")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AiAnalysisJob submit(@Valid @RequestBody AiAnalysisRequest request, Principal principal) {
		return analyses.submit(principal.getName(), request);
	}
	@GetMapping("/analyses")
	public List<AiAnalysisJob> list(@RequestParam String stationId,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate generatedDate, Principal principal) {
		return analyses.list(principal.getName(), stationId, generatedDate);
	}
	@GetMapping("/analyses/{id}")
	public AiAnalysisJob get(@PathVariable String id, Principal principal) { return analyses.get(principal.getName(), id); }
}
