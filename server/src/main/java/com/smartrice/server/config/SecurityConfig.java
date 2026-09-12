package com.smartrice.server.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartrice.server.auth.JsonAuthErrorHandler;
import com.smartrice.server.auth.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import jakarta.servlet.DispatcherType;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	/** 密码哈希：带 {bcrypt} 前缀的委托编码器，便于日后无痛升级算法。 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	SecretKey jwtSecretKey(AppProperties props) {
		String configured = props.getAuth().getJwtSecret();
		byte[] bytes;
		if (configured == null || configured.isBlank()) {
			bytes = new byte[64];
			new SecureRandom().nextBytes(bytes);
			log.warn("未配置 app.auth.jwt-secret，已随机生成签名密钥：服务重启后已签发的访问令牌将全部失效（记住登录不受影响）");
		}
		else {
			bytes = configured.getBytes(StandardCharsets.UTF_8);
			if (bytes.length < 32) {
				throw new IllegalStateException("app.auth.jwt-secret 长度至少 32 字节");
			}
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey key) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey key) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtService.ISSUER));
		return decoder;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AppProperties props) {
		CorsConfiguration config = new CorsConfiguration();
		List<String> origins = props.getCors().getAllowedOriginList();
		if (!origins.isEmpty()) {
			config.setAllowedOrigins(origins);
		}
		List<String> patterns = props.getCors().getAllowedOriginPatternList();
		if (!patterns.isEmpty()) {
			config.setAllowedOriginPatterns(patterns);
		}
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties props,
			JsonAuthErrorHandler errorHandler) throws Exception {
		// 不能按类型注入 CorsConfigurationSource：MVC 的 HandlerMappingIntrospector 也实现了该接口
		CorsConfigurationSource cors = corsConfigurationSource(props);
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(JwtService.CLAIM_ROLE);
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
		jwtConverter.setJwtGrantedAuthoritiesConverter(authorities);

		http
			.csrf(AbstractHttpConfigurer::disable)
			.cors(c -> c.configurationSource(cors))
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.headers(h -> h.frameOptions(f -> f.sameOrigin()))
			.authorizeHttpRequests(auth -> auth
				// DeferredResult resumes an already authenticated request; the activity
				// service also rechecks the current database user before completing it.
				.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				.requestMatchers("/api/health", "/api/auth/login", "/api/auth/remember", "/api/auth/logout",
					"/api/astrbot/devices/**").permitAll()
				.requestMatchers("/h2-console/**", "/error").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(rs -> rs
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
				.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler))
			.exceptionHandling(e -> e
				.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.anonymous(Customizer.withDefaults());

		return http.build();
	}
}
