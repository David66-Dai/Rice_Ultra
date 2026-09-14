package com.smartrice.server.astrbot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Server-side AstrBot credential and exact chat-identity to platform-user mappings. */
@Component
@ConfigurationProperties(prefix = "app.astrbot-control")
public class AstrBotControlProperties {

	private boolean enabled;
	private String apiToken = "";
	private boolean allowTestControl;
	private Duration confirmationTtl = Duration.ofMinutes(2);
	private int defaultSprayDurationSeconds = 60;
	private int maxDurationSeconds = 300;
	private int maxQueryRangeDays = 31;
	private List<IdentityBinding> identities = new ArrayList<>();

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getApiToken() { return apiToken; }
	public void setApiToken(String apiToken) { this.apiToken = apiToken; }
	public boolean isAllowTestControl() { return allowTestControl; }
	public void setAllowTestControl(boolean allowTestControl) { this.allowTestControl = allowTestControl; }
	public Duration getConfirmationTtl() { return confirmationTtl; }
	public void setConfirmationTtl(Duration confirmationTtl) { this.confirmationTtl = confirmationTtl; }
	public int getDefaultSprayDurationSeconds() { return defaultSprayDurationSeconds; }
	public void setDefaultSprayDurationSeconds(int defaultSprayDurationSeconds) {
		this.defaultSprayDurationSeconds = defaultSprayDurationSeconds;
	}
	public int getMaxDurationSeconds() { return maxDurationSeconds; }
	public void setMaxDurationSeconds(int maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }
	public int getMaxQueryRangeDays() { return maxQueryRangeDays; }
	public void setMaxQueryRangeDays(int maxQueryRangeDays) { this.maxQueryRangeDays = maxQueryRangeDays; }
	public List<IdentityBinding> getIdentities() { return identities; }
	public void setIdentities(List<IdentityBinding> identities) {
		this.identities = identities == null ? new ArrayList<>() : new ArrayList<>(identities);
	}

	public static class IdentityBinding {
		private String umo = "";
		private String senderId = "";
		private String username = "";

		public String getUmo() { return umo; }
		public void setUmo(String umo) { this.umo = umo; }
		public String getSenderId() { return senderId; }
		public void setSenderId(String senderId) { this.senderId = senderId; }
		public String getUsername() { return username; }
		public void setUsername(String username) { this.username = username; }
	}
}
