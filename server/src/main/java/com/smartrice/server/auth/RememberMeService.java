package com.smartrice.server.auth;

import com.smartrice.server.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * “记住密码”实现：不保存用户密码，而是下发一个长期、可撤销、每次使用即轮换的持久化令牌。
 * 客户端持有 {@code series.token}，服务端只保存 token 的 SHA-256 哈希，数据库泄露也无法伪造。
 * 若某个 series 收到了错误的 token，视为令牌被盗，立即吊销该用户的全部记住登录。
 */
@Service
public class RememberMeService {

	private static final Logger log = LoggerFactory.getLogger(RememberMeService.class);
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

	private final RememberMeTokenRepository repository;
	private final AppProperties props;
	private final SecureRandom random = new SecureRandom();

	public RememberMeService(RememberMeTokenRepository repository, AppProperties props) {
		this.repository = repository;
		this.props = props;
	}

	public record Rotation(UserAccount user, String newRawToken) {
	}

	@Transactional
	public String issue(UserAccount user, String userAgent) {
		Instant now = Instant.now();
		String series = randomToken(16);
		String token = randomToken(32);
		repository.save(new RememberMeToken(
			series, sha256(token), user, trim(userAgent), now, now.plus(props.getAuth().getRememberMeTtl())));
		return series + "." + token;
	}

	/** 校验失败抛出 AuthException 时不回滚，保证“吊销 / 删除过期令牌”能真正落库。 */
	@Transactional(noRollbackFor = AuthException.class)
	public Rotation verifyAndRotate(String rawToken, String userAgent) {
		Parsed parsed = parse(rawToken).orElseThrow(AuthException::invalidRememberToken);
		RememberMeToken stored = repository.findById(parsed.series()).orElseThrow(AuthException::invalidRememberToken);

		byte[] presented = sha256(parsed.token()).getBytes(StandardCharsets.US_ASCII);
		byte[] expected = stored.getTokenHash().getBytes(StandardCharsets.US_ASCII);
		if (!MessageDigest.isEqual(presented, expected)) {
			// series 正确但 token 错误：极可能令牌已被复制盗用，吊销该用户全部记住登录
			Long userId = stored.getUser().getId();
			repository.deleteAllByUserId(userId);
			log.warn("记住登录令牌校验失败（series={}），已吊销用户 {} 的全部记住登录", parsed.series(), userId);
			throw AuthException.invalidRememberToken();
		}

		Instant now = Instant.now();
		if (stored.isExpiredAt(now)) {
			repository.delete(stored);
			throw AuthException.invalidRememberToken();
		}

		String newToken = randomToken(32);
		stored.setTokenHash(sha256(newToken));
		stored.setLastUsedAt(now);
		stored.setExpiresAt(now.plus(props.getAuth().getRememberMeTtl()));
		stored.setUserAgent(trim(userAgent));

		return new Rotation(stored.getUser(), parsed.series() + "." + newToken);
	}

	@Transactional
	public void revoke(String rawToken) {
		parse(rawToken).ifPresent(p -> repository.deleteById(p.series()));
	}

	@Transactional
	public void revokeAllForUser(Long userId) {
		repository.deleteAllByUserId(userId);
	}

	/** 每天凌晨清理过期令牌。 */
	@Scheduled(cron = "0 0 3 * * *")
	@Transactional
	public void purgeExpired() {
		int removed = repository.deleteAllExpiredBefore(Instant.now());
		if (removed > 0) {
			log.info("已清理 {} 条过期的记住登录令牌", removed);
		}
	}

	private record Parsed(String series, String token) {
	}

	private static Optional<Parsed> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		int dot = raw.indexOf('.');
		if (dot <= 0 || dot == raw.length() - 1 || raw.length() > 256) {
			return Optional.empty();
		}
		return Optional.of(new Parsed(raw.substring(0, dot), raw.substring(dot + 1)));
	}

	private String randomToken(int bytes) {
		byte[] buf = new byte[bytes];
		random.nextBytes(buf);
		return B64.encodeToString(buf);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 不可用", ex);
		}
	}

	private static String trim(String userAgent) {
		if (userAgent == null) {
			return null;
		}
		return userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
	}
}
