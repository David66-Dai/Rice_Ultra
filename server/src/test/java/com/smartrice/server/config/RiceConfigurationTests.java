package com.smartrice.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

class RiceConfigurationTests {

	@TempDir
	Path directory;

	@Test
	void discoversSameYamlFromProjectServerAndNestedIdeWorkingDirectories() throws Exception {
		Path yaml = ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of());
		for (Path cwd : new Path[] {directory, directory.resolve("server"), directory.resolve("server/target/classes")}) {
			Files.createDirectories(cwd);
			assertThat(RiceConfiguration.configureLocation(environment, cwd)).isEqualTo(yaml);
			assertThat(environment.getProperty("rice.config.location")).isEqualTo(yaml.toUri().toString());
		}
	}

	@Test
	void environmentPathTakesPriorityAndRelativePathsUseTheWorkingDirectory() throws Exception {
		Path yaml = ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of("RICE_CONFIG_PATH", "conf/config.yaml"));
		environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--rice.config.path=missing.yaml"));
		assertThat(RiceConfiguration.configureLocation(environment, directory)).isEqualTo(yaml);
		StandardEnvironment explicit = ConfigurationFixtures.environment(directory, Map.of());
		explicit.getPropertySources().addFirst(new MapPropertySource("systemPropertiesFixture",
			Map.of("rice.config.path", yaml.toString())));
		assertThat(RiceConfiguration.configureLocation(explicit, directory.resolve("unrelated"))).isEqualTo(yaml);
	}

	@Test
	void invalidExplicitPathNeverFallsBackAndDoesNotEchoItsValue() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment environment = ConfigurationFixtures.environment(directory,
			Map.of("RICE_CONFIG_PATH", "missing-SECRET_SENTINEL.yaml"));
		assertThatThrownBy(() -> RiceConfiguration.configureLocation(environment, directory))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("指定")
			.hasMessageNotContaining("SECRET_SENTINEL").hasNoCause();
	}

	@Test
	void missingDefaultConfigurationFailsBeforeConfigData() throws Exception {
		StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of());
		assertThatThrownBy(() -> RiceConfiguration.configureLocation(environment, directory))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未找到 conf/config.yaml");
	}

	@Test
	void explicitlyEmptyPathDoesNotFallBackToAnExistingDefault() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		for (String value : new String[] {"", "   "}) {
			StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of("RICE_CONFIG_PATH", value));
			assertThatThrownBy(() -> RiceConfiguration.configureLocation(environment, directory))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空").hasNoCause();
		}
	}

	@Test
	void malformedYamlDoesNotExposeSecretLinesOrParserCause() throws Exception {
		Path yaml = ConfigurationFixtures.writeConfiguration(directory);
		Files.writeString(yaml, "password: [SECRET_SENTINEL\n");
		StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of());
		assertThatThrownBy(() -> RiceConfiguration.configureLocation(environment, directory))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YAML")
			.hasMessageNotContaining("SECRET_SENTINEL").hasNoCause();
	}

	@Test
	void duplicateYamlKeysFailWithoutDisclosingValues() throws Exception {
		Path yaml = ConfigurationFixtures.writeConfiguration(directory);
		Files.writeString(yaml, "password: SECRET_SENTINEL\npassword: OTHER_SECRET\n");
		StandardEnvironment environment = ConfigurationFixtures.environment(directory, Map.of());
		assertThatThrownBy(() -> RiceConfiguration.configureLocation(environment, directory))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YAML")
			.hasMessageNotContaining("SECRET_SENTINEL").hasMessageNotContaining("OTHER_SECRET").hasNoCause();
	}

	@Test
	void mysqlDefaultAndExplicitDevSelectSeparateDatabaseAndSerialSettings() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment mysql = ConfigurationFixtures.environment(directory, Map.of());
		RiceConfiguration.loadEnvironment(mysql, directory);
		assertThat(mysql.getDefaultProfiles()).containsExactly("mysql");
		assertThat(mysql.getProperty("spring.datasource.url")).isEqualTo("jdbc:mysql://fixture.invalid/rice");
		assertThat(mysql.getProperty("app.realtime.serial.enabled", Boolean.class)).isTrue();
		StandardEnvironment dev = ConfigurationFixtures.environment(directory, Map.of("SPRING_PROFILES_ACTIVE", "dev"));
		RiceConfiguration.loadEnvironment(dev, directory);
		assertThat(dev.getActiveProfiles()).containsExactly("dev");
		assertThat(dev.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:fixture");
		assertThat(dev.getProperty("spring.datasource.password")).isEmpty();
		assertThat(dev.getProperty("app.realtime.serial.enabled", Boolean.class)).isFalse();
	}

	@Test
	void environmentAndCommandLineOverridesRetainSpringPrecedence() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment environment = ConfigurationFixtures.environment(directory,
			Map.of("SPRING_DATASOURCE_USERNAME", "environment-user", "SERVER_PORT", "8181"));
		environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--server.port=8282"));
		RiceConfiguration.loadEnvironment(environment, directory);
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("environment-user");
		assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8282);
	}

	@Test
	void inferenceUrlUsesYamlPlaceholdersAndHonorsHostPortOverrides() throws Exception {
		ConfigurationFixtures.writeConfiguration(directory);
		StandardEnvironment defaults = ConfigurationFixtures.environment(directory, Map.of());
		RiceConfiguration.loadEnvironment(defaults, directory);
		assertThat(defaults.getProperty("app.inference.base-url")).isEqualTo("http://127.0.0.1:8001");
		StandardEnvironment overridden = ConfigurationFixtures.environment(directory,
			Map.of("APP_INFERENCE_HOST", "fixture.invalid", "APP_INFERENCE_PORT", "9001"));
		RiceConfiguration.loadEnvironment(overridden, directory);
		assertThat(overridden.getProperty("app.inference.base-url")).isEqualTo("http://fixture.invalid:9001");
	}

	@Test
	void postProcessorIsRegisteredAndRunsBeforeConfigData() throws Exception {
		assertThat(new RiceConfigEnvironmentPostProcessor().getOrder()).isLessThan(ConfigDataEnvironmentPostProcessor.ORDER);
		Enumeration<URL> locations = getClass().getClassLoader()
			.getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION);
		boolean registered = false;
		while (locations.hasMoreElements()) {
			try (InputStream in = locations.nextElement().openStream()) {
				Properties properties = new Properties();
				properties.load(in);
				String listed = properties.getProperty(EnvironmentPostProcessor.class.getName(), "");
				if (listed.contains(RiceConfigEnvironmentPostProcessor.class.getName())) {
					registered = true;
					break;
				}
			}
		}
		assertThat(registered).isTrue();
	}
}
