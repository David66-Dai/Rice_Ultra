package com.smartrice.server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Isolated configuration fixtures: no host environment, project YAML or application beans. */
public final class ConfigurationFixtures {

	private ConfigurationFixtures() {
	}

	public static Path writeConfiguration(Path directory) throws IOException {
		Path path = directory.resolve("conf/config.yaml");
		Files.createDirectories(path.getParent());
		Files.writeString(path, """
			spring:
			  profiles:
			    default: mysql
			server:
			  port: 8080
			app:
			  inference:
			    host: "127.0.0.1"
			    port: 8001
			    base-url: "http://${app.inference.host}:${app.inference.port}"
			---
			spring:
			  config:
			    activate:
			      on-profile: mysql
			  datasource:
			    url: jdbc:mysql://fixture.invalid/rice
			    username: fixture-mysql
			    password: fixture-mysql-password
			app:
			  realtime:
			    serial:
			      enabled: true
			---
			spring:
			  config:
			    activate:
			      on-profile: dev
			  datasource:
			    url: jdbc:h2:mem:fixture
			    username: sa
			    password: ''
			app:
			  realtime:
			    serial:
			      enabled: false
			""");
		return path;
	}

	public static StandardEnvironment environment(Path directory, Map<String, Object> osEnvironment) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
			new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, osEnvironment));
		Path bootstrap = directory.resolve("fixture-bootstrap.properties");
		Files.writeString(bootstrap, "spring.config.import=${rice.config.location}\n");
		environment.getPropertySources().addLast(new MapPropertySource("fixtureBootstrap",
			Map.of("spring.config.location", bootstrap.toUri().toString())));
		return environment;
	}
}
