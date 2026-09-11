package com.smartrice.server.auth;

import com.smartrice.server.auth.dto.LoginRequest;
import com.smartrice.server.auth.dto.LoginResponse;
import com.smartrice.server.auth.dto.LogoutRequest;
import com.smartrice.server.auth.dto.RememberRequest;
import com.smartrice.server.auth.dto.UserInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	/** 账号密码登录；rememberMe=true 时额外返回 rememberToken。 */
	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		return authService.login(request.username(), request.password(), request.rememberMe(), userAgent(http));
	}

	/** 使用“记住密码”令牌免密登录；返回新的 accessToken 与轮换后的 rememberToken。 */
	@PostMapping("/remember")
	public LoginResponse remember(@Valid @RequestBody RememberRequest request, HttpServletRequest http) {
		return authService.loginWithRememberToken(request.rememberToken(), userAgent(http));
	}

	/** 退出登录：吊销记住登录令牌（访问令牌由客户端丢弃，短期自然过期）。 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) LogoutRequest request) {
		authService.logout(request == null ? null : request.rememberToken());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	public UserInfo me(@AuthenticationPrincipal Jwt jwt) {
		if (jwt == null || !(jwt.getClaims().get(JwtService.CLAIM_UID) instanceof Number uid)) {
			throw AuthException.unauthorized();
		}
		return authService.currentUser(uid.longValue());
	}

	private static String userAgent(HttpServletRequest http) {
		return http.getHeader(HttpHeaders.USER_AGENT);
	}
}
