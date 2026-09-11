package com.smartrice.server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/** Shared configuration bootstrap, without creating an application context or any beans. */
public final class RiceConfiguration {

	private static final String LOCATION_SOURCE = "riceConfigurationLocation";

	private RiceConfiguration() {
	}

	public static ConfigurableEnvironment loadEnvironment(String... args) {
		StandardEnvironment environment = new StandardEnvironment();
		if (args.length > 0) {
			environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
		}
		return loadEnvironment(environment, Path.of(""));
	}

	public static ConfigurableEnvironment loadEnvironment(ConfigurableEnvironment environment, Path workingDirectory) {
		configureLocation(environment, workingDirectory);
		try {
			ConfigDataEnvironmentPostProcessor.applyTo(environment);
			return environment;
		}
		catch (RuntimeException ex) {
			// ConfigData exceptions can include YAML source lines and placeholder values.
			throw new IllegalArgumentException("无法加载统一配置，请检查 YAML、profile 和环境变量设置。");
		}
	}

	public static Path configureLocation(ConfigurableEnvironment environment, Path workingDirectory) {
		Path path = locate(environment, workingDirectory);
		try {
			// Parse once before ConfigData so malformed YAML never prints a secret-bearing source line.
			new YamlPropertySourceLoader().load("riceConfigurationValidation", new FileSystemResource(path));
		}
		catch (IOException | RuntimeException ex) {
			throw new IllegalArgumentException("无法解析统一配置，请检查 conf/config.yaml 的 YAML 格式和读取权限。");
		}
		environment.getPropertySources().addFirst(new MapPropertySource(LOCATION_SOURCE,
			Map.of("rice.config.location", path.toUri().toString())));
		return path;
	}

	static Path locate(ConfigurableEnvironment environment, Path workingDirectory) {
		try {
			Path cwd = workingDirectory.toAbsolutePath().normalize();
			String configured = environment.getProperty("RICE_CONFIG_PATH");
			if (configured == null) {
				configured = environment.getProperty("rice.config.path");
			}
			if (configured != null) {
				if (configured.isBlank()) {
					throw new IllegalArgumentException("统一配置路径不能为空，请检查 RICE_CONFIG_PATH / rice.config.path。");
				}
				Path supplied = Path.of(configured.trim());
				Path path = (supplied.isAbsolute() ? supplied : cwd.resolve(supplied)).normalize();
				if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
					throw new IllegalArgumentException("指定的统一配置文件不存在或不可读，请检查 RICE_CONFIG_PATH / rice.config.path。");
				}
				return path;
			}
			for (Path directory = cwd; directory != null; directory = directory.getParent()) {
				Path candidate = directory.resolve("conf/config.yaml");
				if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
					return candidate;
				}
			}
			throw new IllegalArgumentException("未找到 conf/config.yaml；请从项目目录启动，或通过 RICE_CONFIG_PATH 指定配置文件。");
		}
		catch (InvalidPathException | SecurityException ex) {
			throw new IllegalArgumentException("统一配置路径无效或不可读，请检查 RICE_CONFIG_PATH / rice.config.path。");
		}
	}
}
