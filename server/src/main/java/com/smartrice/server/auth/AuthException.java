package com.smartrice.server.auth;

import org.springframework.http.HttpStatus;

/** 登录 / 令牌相关业务异常，统一以 JSON {code, message} 返回。 */
public class AuthException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public AuthException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public static AuthException invalidCredentials() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "账号或密码错误");
	}

	public static AuthException accountLocked(long minutesLeft) {
		return new AuthException(HttpStatus.LOCKED, "account_locked",
			"密码错误次数过多，账号已锁定，请 " + Math.max(1, minutesLeft) + " 分钟后再试");
	}

	public static AuthException accountDisabled() {
		return new AuthException(HttpStatus.FORBIDDEN, "account_disabled", "账号已停用，请联系管理员");
	}

	/** 服务账号：后端按精确身份映射授权，禁止任何形式的网页登录。 */
	public static AuthException loginDisabled() {
		return new AuthException(HttpStatus.FORBIDDEN, "login_disabled", "该账号为服务账号，不能登录网页");
	}

	public static AuthException invalidRememberToken() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "invalid_remember_token", "记住登录已失效，请重新输入密码");
	}

	public static AuthException unauthorized() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "unauthorized", "未登录或登录已过期");
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}
}
