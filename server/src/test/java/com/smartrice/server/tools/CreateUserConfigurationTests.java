package com.smartrice.server.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartrice.server.config.ConfigurationFixtures;
import com.smartrice.server.config.RiceConfiguration;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class CreateUserConfigurationTests {

	@TempDir
	Path directory;

	@Test
	void accountToolUsesTheSameProfileAndEnvironmentAsTheServerWithoutOpeningDatabase() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment environment = ConfigurationFixtures.environment(directory,
			Map.of("SPRING_PROFILES_ACTIVE", "dev", "SPRING_DATASOURCE_USERNAME", "override-user"));
		RiceConfiguration.loadEnvironment(environment, directory);
		CreateUserTool.DbConfig db = CreateUserTool.DbConfig.load(environment, Map.of());
		assertThat(db.url).isEqualTo(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:fixture");
		assertThat(db.username).isEqualTo("override-user");
		assertThat(db.password).isEmpty();
		assertThat(environment.getProperty("app.realtime.serial.enabled", Boolean.class)).isFalse();
	}

	@Test
	void dedicatedAccountOverridesWinAndPreserveAnExplicitlyEmptyPassword() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of());
		RiceConfiguration.loadEnvironment(environment, directory);
		CreateUserTool.DbConfig db = CreateUserTool.DbConfig.load(environment,
			Map.of("CREATE_USER_DB_URL", "jdbc:h2:mem:override", "CREATE_USER_DB_USERNAME", "tool-user", "CREATE_USER_DB_PASSWORD", ""));
		assertThat(db.url).isEqualTo("jdbc:h2:mem:override");
		assertThat(db.username).isEqualTo("tool-user");
		assertThat(db.password).isEmpty();
	}

	@Test
	void configurationOptionsDoNotBecomeAccountArguments() {
		CreateUserTool.Request request = CreateUserTool.Request.parse(new String[] {
			"--username", "fixture", "--password", "FixturePassword1", "--spring.profiles.active=dev",
			"--rice.config.path", "fixture.yaml", "--spring.datasource.username=override"
		});
		assertThat(request.username).isEqualTo("fixture");
		assertThat(request.password).isEqualTo("FixturePassword1");
		assertThat(request.configurationArgs).containsExactly("--spring.profiles.active=dev",
			"--rice.config.path=fixture.yaml", "--spring.datasource.username=override");
	}

	@Test
	void unresolvedDatabasePlaceholdersDoNotExposeTheirOriginalValuesOrCause() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of());
		RiceConfiguration.loadEnvironment(environment, directory);
		for (String field : new String[] {"url", "username", "password"}) {
			environment.getPropertySources().addFirst(new MapPropertySource("unresolvedDatabaseFixture",
				Map.of("spring.datasource." + field, "SECRET_SENTINEL_${MISSING_FIXTURE_VALUE}")));
			assertThatThrownBy(() -> CreateUserTool.DbConfig.load(environment, Map.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("无法解析数据库配置，请检查统一配置中的占位符和环境变量。")
				.hasMessageNotContaining("SECRET_SENTINEL")
				.hasMessageNotContaining("MISSING_FIXTURE_VALUE")
				.hasNoCause();
		}
	}
}
