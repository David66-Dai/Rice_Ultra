package com.smartrice.server.diagnosis;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

	private final DiagnosisService diagnoses;

	public DiagnosisController(DiagnosisService diagnoses) {
		this.diagnoses = diagnoses;
	}

	@PostMapping(value = "/leaf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public DiagnosisResponse leaf(@RequestParam String stationId, @RequestPart("file") MultipartFile file) {
		return diagnoses.diagnose(stationId, "leaf", file);
	}

	@PostMapping(value = "/pest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public DiagnosisResponse pest(@RequestParam String stationId, @RequestPart("file") MultipartFile file) {
		return diagnoses.diagnose(stationId, "pest", file);
	}

	@GetMapping("/stations")
	public StationAlertListResponse stations() {
		return diagnoses.stationAlerts();
	}
}
