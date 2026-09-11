package com.smartrice.server.auth.dto;

import com.smartrice.server.auth.UserAccount;
import java.time.Instant;

public record UserInfo(Long id, String username, String displayName, String role, Instant lastLoginAt) {

	public static UserInfo from(UserAccount user) {
		return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.getLastLoginAt());
	}
}
