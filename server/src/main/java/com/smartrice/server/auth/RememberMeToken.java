package com.smartrice.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * “记住密码”持久化令牌（series + 一次性 token，token 只存 SHA-256 哈希）。
 * 客户端保存 series.token；每次使用都会轮换 token，旧值立即失效。
 */
@Entity
@Table(
	name = "remember_me_token",
	indexes = @Index(name = "idx_remember_me_token_user", columnList = "user_id")
)
public class RememberMeToken {

	@Id
	@Column(length = 32)
	private String series;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private UserAccount user;

	@Column(name = "user_agent", length = 255)
	private String userAgent;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected RememberMeToken() {
	}

	public RememberMeToken(String series, String tokenHash, UserAccount user, String userAgent, Instant now, Instant expiresAt) {
		this.series = series;
		this.tokenHash = tokenHash;
		this.user = user;
		this.userAgent = userAgent;
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	public boolean isExpiredAt(Instant now) {
		return !expiresAt.isAfter(now);
	}

	public String getSeries() {
		return series;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public void setTokenHash(String tokenHash) {
		this.tokenHash = tokenHash;
	}

	public UserAccount getUser() {
		return user;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

	public void setLastUsedAt(Instant lastUsedAt) {
		this.lastUsedAt = lastUsedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}
}
