package com.finplay.api.domain.auth.config;

import com.finplay.api.global.filter.RequestIdFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

	private static final String ALL_PATHS = "/**";

	private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private static final long PREFLIGHT_CACHE_SECONDS = 3600L;

	@Bean
	public CorsConfigurationSource corsConfigurationSource(
		@Value("${finplay.cors.allowed-origins}")
		List<String> allowedOrigins) {

		List<String> origins = validate(allowedOrigins);

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(origins);
		configuration.setAllowedMethods(ALLOWED_METHODS);
		configuration.setAllowedHeaders(
			List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, IDEMPOTENCY_KEY_HEADER));
		configuration.setExposedHeaders(List.of(RequestIdFilter.REQUEST_ID_HEADER));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(PREFLIGHT_CACHE_SECONDS);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration(ALL_PATHS, configuration);
		return source;
	}

	private static List<String> validate(List<String> allowedOrigins) {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			throw new IllegalStateException(
				"CORS 허용 오리진이 비어 있습니다. CORS_ALLOWED_ORIGINS를 설정하세요.");
		}
		List<String> origins = allowedOrigins.stream().map(String::trim).toList();
		for (String origin : origins) {
			if (origin.isEmpty()) {
				throw new IllegalStateException(
					"CORS 허용 오리진에 빈 값이 있습니다. CORS_ALLOWED_ORIGINS의 콤마 구분을 확인하세요: " + allowedOrigins);
			}
			if (origin.endsWith("/")) {
				throw new IllegalStateException(
					"CORS 허용 오리진은 끝에 슬래시를 붙이지 않습니다(스킴+호스트+포트까지만). 확인 대상: " + origin);
			}
			if (!origin.equals("*") && !origin.startsWith("http://") && !origin.startsWith("https://")) {
				throw new IllegalStateException(
					"CORS 허용 오리진에는 스킴이 필요합니다(http:// 또는 https://). 확인 대상: " + origin);
			}
		}
		return origins;
	}
}
