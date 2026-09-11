package com.smartrice.server.realtime;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** An empty allowlist denies actuator control, including accounts with the ADMIN role. */
@Component
@ConfigurationProperties(prefix = "app.devices")
public class DevicesProperties {

	private List<String> controlUsers = new ArrayList<>();

	public List<String> getControlUsers() {
		return controlUsers;
	}

	public void setControlUsers(List<String> controlUsers) {
		this.controlUsers = controlUsers == null ? new ArrayList<>() : new ArrayList<>(controlUsers);
	}

	public boolean permits(String username) {
		return username != null && controlUsers.stream()
			.anyMatch(allowed -> allowed != null && allowed.trim().equalsIgnoreCase(username));
	}
}
