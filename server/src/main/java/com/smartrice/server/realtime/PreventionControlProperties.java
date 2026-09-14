package com.smartrice.server.realtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.prevention-control")
public class PreventionControlProperties {

	private boolean requireAstrBotConfirmationDefault = true;
	private boolean spraySafetyEnabled = true;
	private double maxSprayWindSpeedMs = 3.0;
	private Duration sensorMaxAge = Duration.ofMinutes(2);
	private Duration leafEvidenceMaxAge = Duration.ofHours(24);
	private Duration diagnosisConfirmationTtl = Duration.ofMinutes(10);
	private boolean astrBotAlertEnabled;
	private String astrBotBaseUrl = "http://127.0.0.1:6185";
	private String astrBotApiKey = "";
	private List<String> alertUmos = new ArrayList<>();

	public boolean isRequireAstrBotConfirmationDefault() { return requireAstrBotConfirmationDefault; }
	public void setRequireAstrBotConfirmationDefault(boolean value) { this.requireAstrBotConfirmationDefault = value; }
	public boolean isSpraySafetyEnabled() { return spraySafetyEnabled; }
	public void setSpraySafetyEnabled(boolean value) { this.spraySafetyEnabled = value; }
	public double getMaxSprayWindSpeedMs() { return maxSprayWindSpeedMs; }
	public void setMaxSprayWindSpeedMs(double value) { this.maxSprayWindSpeedMs = value; }
	public Duration getSensorMaxAge() { return sensorMaxAge; }
	public void setSensorMaxAge(Duration value) { this.sensorMaxAge = value; }
	public Duration getLeafEvidenceMaxAge() { return leafEvidenceMaxAge; }
	public void setLeafEvidenceMaxAge(Duration value) { this.leafEvidenceMaxAge = value; }
	public Duration getDiagnosisConfirmationTtl() { return diagnosisConfirmationTtl; }
	public void setDiagnosisConfirmationTtl(Duration value) { this.diagnosisConfirmationTtl = value; }
	public boolean isAstrBotAlertEnabled() { return astrBotAlertEnabled; }
	public void setAstrBotAlertEnabled(boolean value) { this.astrBotAlertEnabled = value; }
	public String getAstrBotBaseUrl() { return astrBotBaseUrl; }
	public void setAstrBotBaseUrl(String value) { this.astrBotBaseUrl = value; }
	public String getAstrBotApiKey() { return astrBotApiKey; }
	public void setAstrBotApiKey(String value) { this.astrBotApiKey = value; }
	public List<String> getAlertUmos() { return alertUmos; }
	public void setAlertUmos(List<String> values) {
		this.alertUmos = values == null ? new ArrayList<>() : new ArrayList<>(values);
	}
}
