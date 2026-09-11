package com.smartrice.server.auth.dto;

/**
 * @param tokenType     固定为 Bearer
 * @param accessToken   短期访问令牌（JWT）
 * @param expiresIn     访问令牌有效秒数
 * @param rememberToken 勾选“记住密码”时下发的长期令牌，否则为 null；每次使用都会轮换
 * @param user          当前用户信息
 */
public record LoginResponse(
	String tokenType,
	String accessToken,
	long expiresIn,
	String rememberToken,
	UserInfo user
) {
}
