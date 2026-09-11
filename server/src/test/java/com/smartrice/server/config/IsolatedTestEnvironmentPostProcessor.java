package com.smartrice.server.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

/** Registered only on the test classpath; integration tests never use local deployment configuration. */
public final class IsolatedTestEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public int getOrder() {
		return ConfigDataEnvironmentPostProcessor.ORDER - 2;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		try {
			Path yaml = Path.of(new ClassPathResource("rice-test-config.yaml").getURI());
			environment.getPropertySources().addFirst(new MapPropertySource("isolatedTestInfrastructure", Map.of(
				"RICE_CONFIG_PATH", yaml.toString(),
				"spring.config.location", "classpath:/application.properties",
				"spring.datasource.url", "jdbc:h2:mem:rice_test_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
				"spring.datasource.driver-class-name", "org.h2.Driver",
				"spring.datasource.username", "sa",
				"spring.datasource.password", "",
				"spring.jpa.hibernate.ddl-auto", "create-drop",
				"app.realtime.serial.enabled", "false"
			)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法读取隔离测试配置；禁止回退到本机配置。");
		}
	}
}
