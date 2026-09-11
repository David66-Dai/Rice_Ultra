package com.smartrice.server.auth;

import com.smartrice.server.auth.dto.LoginResponse;
import com.smartrice.server.auth.dto.UserInfo;
import com.smartrice.server.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);
	private static final String TOKEN_TYPE = "Bearer";

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final RememberMeService rememberMeService;
	private final AppProperties props;

	/** 账号不存在时也做一次哈希比对，避免通过响应时间探测账号是否存在。 */
	private final String dummyHash;

	public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
			RememberMeService rememberMeService, AppProperties props) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.rememberMeService = rememberMeService;
		this.props = props;
		this.dummyHash = passwordEncoder.encode("timing-equalizer-" + System.nanoTime());
	}

	@Transactional(noRollbackFor = AuthException.class)
	public LoginResponse login(String username, String password, boolean rememberMe, String userAgent) {
		UserAccount user = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
		if (user == null) {
			passwordEncoder.matches(password, dummyHash);
			throw AuthException.invalidCredentials();
		}

		Instant now = Instant.now();
		if (!user.isEnabled()) {
			throw AuthException.accountDisabled();
		}
		if (user.isLockedAt(now)) {
			throw AuthException.accountLocked(minutesUntil(user.getLockedUntil(), now));
		}

		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			registerFailure(user, now);
			throw AuthException.invalidCredentials();
		}

		// 登录成功：清零失败计数，必要时用更强的参数重新哈希
		user.setFailedAttempts(0);
		user.setLockedUntil(null);
		user.setLastLoginAt(now);
		if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
			user.setPasswordHash(passwordEncoder.encode(password));
		}

		String rememberToken = rememberMe ? rememberMeService.issue(user, userAgent) : null;
		return buildResponse(user, rememberToken);
	}

	@Transactional(noRollbackFor = AuthException.class)
	public LoginResponse loginWithRememberToken(String rawToken, String userAgent) {
		RememberMeService.Rotation rotation = rememberMeService.verifyAndRotate(rawToken, userAgent);
		UserAccount user = rotation.user();

		Instant now = Instant.now();
		if (!user.isEnabled()) {
			rememberMeService.revokeAllForUser(user.getId());
			throw AuthException.accountDisabled();
		}
		if (user.isLockedAt(now)) {
			throw AuthException.accountLocked(minutesUntil(user.getLockedUntil(), now));
		}

		user.setLastLoginAt(now);
		return buildResponse(user, rotation.newRawToken());
	}

	@Transactional
	public void logout(String rememberToken) {
		if (rememberToken != null && !rememberToken.isBlank()) {
			rememberMeService.revoke(rememberToken);
		}
	}

	@Transactional(readOnly = true)
	public UserInfo currentUser(Long userId) {
		UserAccount user = users.findById(userId).orElseThrow(AuthException::unauthorized);
		if (!user.isEnabled()) {
			throw AuthException.accountDisabled();
		}
		return UserInfo.from(user);
	}

	private void registerFailure(UserAccount user, Instant now) {
		int attempts = user.getFailedAttempts() + 1;
		if (attempts >= props.getAuth().getMaxFailedAttempts()) {
			user.setFailedAttempts(0);
			user.setLockedUntil(now.plus(props.getAuth().getLockDuration()));
			log.warn("账号 {} 连续密码错误 {} 次，已锁定至 {}", user.getUsername(), attempts, user.getLockedUntil());
		}
		else {
			user.setFailedAttempts(attempts);
		}
	}

	private LoginResponse buildResponse(UserAccount user, String rememberToken) {
		JwtService.IssuedToken access = jwtService.issue(user);
		return new LoginResponse(TOKEN_TYPE, access.token(), access.expiresInSeconds(), rememberToken, UserInfo.from(user));
	}

	private static long minutesUntil(Instant until, Instant now) {
		return (long) Math.ceil(Duration.between(now, until).toSeconds() / 60.0);
	}
}
