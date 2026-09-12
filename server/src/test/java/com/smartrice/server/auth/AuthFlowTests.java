package com.smartrice.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
	"app.auth.bootstrap-admin.enabled=true",
	"app.auth.bootstrap-admin.username=tester",
	"app.auth.bootstrap-admin.password=Secret#123",
	"app.auth.max-failed-attempts=3",
	"app.auth.lock-duration=10m"
})
class AuthFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	@Autowired
	UserAccountRepository users;

	@Autowired
	RememberMeTokenRepository tokens;

	@BeforeEach
	void resetLockState() {
		UserAccount user = users.findByUsernameIgnoreCase("tester").orElseThrow();
		user.setFailedAttempts(0);
		user.setLockedUntil(null);
		users.save(user);
		tokens.deleteAll();
	}

	@Test
	void passwordIsStoredHashedNotPlain() {
		UserAccount user = users.findByUsernameIgnoreCase("tester").orElseThrow();
		assertThat(user.getPasswordHash()).startsWith("{bcrypt}$2");
		assertThat(user.getPasswordHash()).doesNotContain("Secret#123");
	}

	@Test
	void loginReturnsAccessTokenAndMeWorks() throws Exception {
		JsonNode body = login("tester", "Secret#123", false, 200);
		assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
		assertThat(body.get("accessToken").asText()).isNotBlank();
		assertThat(body.get("rememberToken").isNull()).isTrue();
		assertThat(body.get("user").get("username").asText()).isEqualTo("tester");

		mvc.perform(get("/api/auth/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + body.get("accessToken").asText()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("tester"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void protectedEndpointRejectsMissingOrBogusToken() throws Exception {
		mvc.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("unauthorized"));

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("unauthorized"));
	}

	@Test
	void wrongPasswordIsRejectedWithGenericMessage() throws Exception {
		JsonNode body = login("tester", "nope", false, 401);
		assertThat(body.get("code").asText()).isEqualTo("invalid_credentials");
		assertThat(body.get("message").asText()).isEqualTo("账号或密码错误");

		JsonNode unknown = login("ghost", "nope", false, 401);
		assertThat(unknown.get("code").asText()).isEqualTo("invalid_credentials");
	}

	@Test
	void accountLocksAfterTooManyFailures() throws Exception {
		login("tester", "bad-1", false, 401);
		login("tester", "bad-2", false, 401);
		login("tester", "bad-3", false, 401);

		JsonNode locked = login("tester", "Secret#123", false, 423);
		assertThat(locked.get("code").asText()).isEqualTo("account_locked");
	}

	@Test
	void rememberTokenLogsInAndRotates() throws Exception {
		JsonNode first = login("tester", "Secret#123", true, 200);
		String rememberToken = first.get("rememberToken").asText();
		assertThat(rememberToken).contains(".");
		assertThat(tokens.count()).isEqualTo(1);

		JsonNode second = remember(rememberToken, 200);
		String rotated = second.get("rememberToken").asText();
		assertThat(rotated).isNotEqualTo(rememberToken);
		assertThat(second.get("accessToken").asText()).isNotBlank();
		assertThat(second.get("user").get("username").asText()).isEqualTo("tester");

		// 旧令牌已轮换失效，重放视为盗用：吊销该用户全部记住登录
		JsonNode replay = remember(rememberToken, 401);
		assertThat(replay.get("code").asText()).isEqualTo("invalid_remember_token");
		assertThat(tokens.count()).isZero();

		remember(rotated, 401);
	}

	@Test
	void logoutRevokesRememberToken() throws Exception {
		JsonNode body = login("tester", "Secret#123", true, 200);
		String rememberToken = body.get("rememberToken").asText();

		mvc.perform(post("/api/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rememberToken\":\"" + rememberToken + "\"}"))
			.andExpect(status().isNoContent());

		assertThat(tokens.count()).isZero();
		remember(rememberToken, 401);
	}

	@Test
	void blankCredentialsFailValidation() throws Exception {
		JsonNode body = login("", "", false, 400);
		assertThat(body.get("code").asText()).isEqualTo("validation_failed");
	}

	private JsonNode login(String username, String password, boolean rememberMe, int expectedStatus) throws Exception {
		String payload = json.writeValueAsString(new LoginPayload(username, password, rememberMe));
		MvcResult result = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.USER_AGENT, "junit")
				.content(payload))
			.andExpect(status().is(expectedStatus))
			.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode remember(String rememberToken, int expectedStatus) throws Exception {
		MvcResult result = mvc.perform(post("/api/auth/remember")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rememberToken\":\"" + rememberToken + "\"}"))
			.andExpect(status().is(expectedStatus))
			.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	record LoginPayload(String username, String password, boolean rememberMe) {
	}
}
