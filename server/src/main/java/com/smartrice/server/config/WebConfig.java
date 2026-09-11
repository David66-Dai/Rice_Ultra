package com.smartrice.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** CORS 已随鉴权一起迁到 {@link SecurityConfig}（CorsConfigurationSource）。 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig {

	@Bean
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}
}
