package com.smartrice.server.config;

import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Resolve the external YAML before application.properties imports it. */
public final class RiceConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public int getOrder() {
		return ConfigDataEnvironmentPostProcessor.ORDER - 1;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		RiceConfiguration.configureLocation(environment, Path.of(""));
	}
}
