package com.smartrice.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The AstrBot bot needs a platform account the server can map to, but nobody may ever log in as
 * it. {@code login_enabled=false} is that switch; every existing account keeps working unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ServiceAccountLoginTests {

	private static final String PASSWORD = "Operator#2026";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired RememberMeTokenRepository tokens;
	@Autowired RememberMeService rememberMe;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired EntityManager entityManager;

	@BeforeEach
	void setUp() {
		tokens.deleteAll();
		users.deleteAll();
	}

	@Test
	void existingAccountsKeepLoggingInAndRememberingAfterTheColumnIsAdded() throws Exception {
		UserAccount person = save("operator", "现场操作员", true);
		assertThat(person.isLoginEnabled()).isTrue();

		JsonNode first = login("operator", PASSWORD, true, 200);
		assertThat(first.get("accessToken").asText()).isNotBlank();
		String rememberToken = first.get("rememberToken").asText();
		assertThat(rememberToken).contains(".");

		mvc.perform(get("/api/auth/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + first.get("accessToken").asText()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("operator"));

		JsonNode rotated = remember(rememberToken, 200);
		assertThat(rotated.get("user").get("username").asText()).isEqualTo("operator");
		assertThat(rotated.get("rememberToken").asText()).isNotEqualTo(rememberToken);
	}

	@Test
	@org.springframework.transaction.annotation.Transactional
	void aRowWithNoLoginFlagYetIsTreatedAsAnOrdinaryAccount() throws Exception {
		// ddl-auto=update adds the column without backfilling old rows: NULL must keep meaning
		// "login allowed" so that upgrading the server never locks an existing account out.
		save("legacy", "历史账号", true);
		entityManager.createNativeQuery("UPDATE user_account SET login_enabled = NULL").executeUpdate();
		entityManager.flush();
		entityManager.clear();

		assertThat(users.findByUsernameIgnoreCase("legacy").orElseThrow().isLoginEnabled()).isTrue();
		login("legacy", PASSWORD, false, 200);
	}

	@Test
	void theServiceAccountIsEnabledButCannotPasswordLogin() throws Exception {
		UserAccount bot = save("astrbot", "AstrBot 机器人", false);
		assertThat(bot.isEnabled()).isTrue();
		assertThat(bot.isLoginEnabled()).isFalse();

		JsonNode rejected = login("astrbot", PASSWORD, false, 403);
		assertThat(rejected.get("code").asText()).isEqualTo("login_disabled");
		// The stored hash is never a usable credential either: no blank, no known placeholder.
		String hash = users.findByUsernameIgnoreCase("astrbot").orElseThrow().getPasswordHash();
		assertThat(hash).isNotBlank().startsWith("{bcrypt}$2");
		assertThat(passwordEncoder.matches("", hash)).isFalse();
		for (String guess : new String[] {"astrbot", "password", "123456", "{noop}", PASSWORD}) {
			assertThat(passwordEncoder.matches(guess, hash)).isFalse();
			login("astrbot", guess, false, 403);
		}
	}

	@Test
	void aServiceAccountCanNeitherBeIssuedNorUseARememberToken() throws Exception {
		UserAccount person = save("astrbot", "AstrBot 机器人", true);
		String rememberToken = login("astrbot", PASSWORD, true, 200).get("rememberToken").asText();
		assertThat(tokens.count()).isEqualTo(1);

		// Turning the account into a service account invalidates the token it already handed out.
		person.setLoginEnabled(false);
		users.saveAndFlush(person);

		JsonNode rejected = remember(rememberToken, 403);
		assertThat(rejected.get("code").asText()).isEqualTo("login_disabled");
		assertThat(tokens.count()).isZero();

		// And no new token can ever be minted for it.
		UserAccount bot = users.findByUsernameIgnoreCase("astrbot").orElseThrow();
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> rememberMe.issue(bot, "junit")))
			.isInstanceOf(AuthException.class);
		assertThat(tokens.count()).isZero();
	}

	@Test
	void anAccessTokenMintedBeforeTheSwitchStopsWorkingForTheServiceAccount() throws Exception {
		UserAccount person = save("astrbot", "AstrBot 机器人", true);
		String accessToken = login("astrbot", PASSWORD, false, 200).get("accessToken").asText();
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk());

		person.setLoginEnabled(false);
		users.saveAndFlush(person);

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("login_disabled"));
		mvc.perform(post("/api/devices/control")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of(
					"stationId", "S01", "device", "pump", "enabled", true, "expectedRevision", 0))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("login_disabled"));
	}

	private UserAccount save(String username, String displayName, boolean loginEnabled) {
		UserAccount user = new UserAccount();
		user.setUsername(username);
		user.setDisplayName(displayName);
		// A service account is created the way CreateUserTool --no-login creates it: the column
		// holds a BCrypt hash of a random secret nobody, including this test, ever knows.
		user.setPasswordHash(passwordEncoder.encode(loginEnabled ? PASSWORD : randomSecret()));
		user.setRole(loginEnabled ? "ADMIN" : "SERVICE");
		user.setEnabled(true);
		user.setLoginEnabled(loginEnabled);
		return users.saveAndFlush(user);
	}

	private static String randomSecret() {
		byte[] buffer = new byte[48];
		new java.security.SecureRandom().nextBytes(buffer);
		return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private JsonNode login(String username, String password, boolean rememberMeFlag, int expectedStatus)
			throws Exception {
		MvcResult result = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.USER_AGENT, "junit")
				.content(json.writeValueAsString(
					Map.of("username", username, "password", password, "rememberMe", rememberMeFlag))))
			.andExpect(status().is(expectedStatus))
			.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode remember(String rememberToken, int expectedStatus) throws Exception {
		MvcResult result = mvc.perform(post("/api/auth/remember")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("rememberToken", rememberToken))))
			.andExpect(status().is(expectedStatus))
			.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}
}
