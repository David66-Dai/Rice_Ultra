package com.smartrice.server.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

	private final Inference inference = new Inference();
	private final Cors cors = new Cors();
	private final Auth auth = new Auth();

	public Inference getInference() {
		return inference;
	}

	public Cors getCors() {
		return cors;
	}

	public Auth getAuth() {
		return auth;
	}

	public static class Inference {
		private String baseUrl = "http://127.0.0.1:8001";

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}
	}

	public static class Cors {
		private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";
		private String allowedOriginPatterns = "http://localhost:*,http://127.0.0.1:*,http://10.*:*,http://192.168.*:*,http://172.*:*";

		public List<String> getAllowedOriginList() {
			return splitCsv(allowedOrigins);
		}

		public List<String> getAllowedOriginPatternList() {
			return splitCsv(allowedOriginPatterns);
		}

		public String getAllowedOrigins() {
			return allowedOrigins;
		}

		public void setAllowedOrigins(String allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}

		public String getAllowedOriginPatterns() {
			return allowedOriginPatterns;
		}

		public void setAllowedOriginPatterns(String allowedOriginPatterns) {
			this.allowedOriginPatterns = allowedOriginPatterns;
		}

		private static List<String> splitCsv(String value) {
			if (value == null || value.isBlank()) {
				return List.of();
			}
			return Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
		}
	}

	/** 登录 / 令牌 / 账号锁定相关配置（前缀 app.auth）。 */
	public static class Auth {

		/** HS256 签名密钥，至少 32 字节；留空则每次启动随机生成（仅适合本地开发）。 */
		private String jwtSecret = "";

		/** 访问令牌有效期。 */
		private Duration accessTokenTtl = Duration.ofHours(2);

		/** “记住密码”令牌有效期（每次使用会滑动续期）。 */
		private Duration rememberMeTtl = Duration.ofDays(30);

		/** 连续密码错误多少次后锁定账号。 */
		private int maxFailedAttempts = 5;

		/** 账号锁定时长。 */
		private Duration lockDuration = Duration.ofMinutes(15);

		private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

		public String getJwtSecret() {
			return jwtSecret;
		}

		public void setJwtSecret(String jwtSecret) {
			this.jwtSecret = jwtSecret;
		}

		public Duration getAccessTokenTtl() {
			return accessTokenTtl;
		}

		public void setAccessTokenTtl(Duration accessTokenTtl) {
			this.accessTokenTtl = accessTokenTtl;
		}

		public Duration getRememberMeTtl() {
			return rememberMeTtl;
		}

		public void setRememberMeTtl(Duration rememberMeTtl) {
			this.rememberMeTtl = rememberMeTtl;
		}

		public int getMaxFailedAttempts() {
			return maxFailedAttempts;
		}

		public void setMaxFailedAttempts(int maxFailedAttempts) {
			this.maxFailedAttempts = maxFailedAttempts;
		}

		public Duration getLockDuration() {
			return lockDuration;
		}

		public void setLockDuration(Duration lockDuration) {
			this.lockDuration = lockDuration;
		}

		public BootstrapAdmin getBootstrapAdmin() {
			return bootstrapAdmin;
		}
	}

	/** 系统没有注册功能：首次启动且用户表为空时自动创建的管理员账号。 */
	public static class BootstrapAdmin {
		private boolean enabled = true;
		private String username = "admin";
		private String password = "SmartRice@2026";
		private String displayName = "系统管理员";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public String getDisplayName() {
			return displayName;
		}

		public void setDisplayName(String displayName) {
			this.displayName = displayName;
		}
	}
}
