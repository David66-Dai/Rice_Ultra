package com.smartrice.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** 系统登录账号。密码只保存加盐哈希（BCrypt），永不存明文。 */
@Entity
@Table(
	name = "user_account",
	indexes = @Index(name = "uk_user_account_username", columnList = "username", unique = true)
)
public class UserAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 128)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 64)
	private String displayName;

	@Column(nullable = false, length = 32)
	private String role = "ADMIN";

	@Column(nullable = false)
	private boolean enabled = true;

	/**
	 * 是否允许用密码 / 记住登录进入网页端。服务账号（例如 AstrBot 机器人）保持
	 * enabled=true 但 login_enabled=false：后端仍可按精确身份映射授权它，
	 * 任何人都无法用它登录网页。
	 *
	 * <p>刻意声明为可空并把 {@code null} 视作“允许登录”：ddl-auto=update 给已有表补列时
	 * 不会给旧行填 0，升级后所有既有账号的登录行为完全不变。只有显式的 {@code false} 才禁用登录。</p>
	 */
	@Column(name = "login_enabled")
	private Boolean loginEnabled = Boolean.TRUE;

	@Column(name = "failed_attempts", nullable = false)
	private int failedAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	public boolean isLockedAt(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isLoginEnabled() {
		return loginEnabled == null || loginEnabled;
	}

	public void setLoginEnabled(boolean loginEnabled) {
		this.loginEnabled = loginEnabled;
	}

	public int getFailedAttempts() {
		return failedAttempts;
	}

	public void setFailedAttempts(int failedAttempts) {
		this.failedAttempts = failedAttempts;
	}

	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public void setLockedUntil(Instant lockedUntil) {
		this.lockedUntil = lockedUntil;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public void setLastLoginAt(Instant lastLoginAt) {
		this.lastLoginAt = lastLoginAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
