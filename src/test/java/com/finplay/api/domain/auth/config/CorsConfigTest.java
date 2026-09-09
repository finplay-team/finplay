package com.finplay.api.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.filter.RequestIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@WebMvcTest(controllers = CorsConfigTest.CorsTestController.class)
@Import({
	CorsConfigTest.CorsTestController.class,
	CorsConfigTest.TestTokenProviderConfig.class,
	SecurityConfig.class,
	CorsConfig.class
})
@TestPropertySource(properties = "finplay.cors.allowed-origins=https://www.finplay.site,http://finplay-frontend.s3-website.ap-northeast-2.amazonaws.com")
class CorsConfigTest {

	private static final String ALLOWED_ORIGIN = "https://www.finplay.site";
	private static final String ALLOWED_S3_ORIGIN = "http://finplay-frontend.s3-website.ap-northeast-2.amazonaws.com";
	private static final String DISALLOWED_ORIGIN = "https://evil.example.com";

	private static final String PUBLIC_PATH = "/actuator/health";
	private static final String PROTECTED_PATH = "/test/cors-protected";

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest(name = "preflight {0}")
	@ValueSource(strings = {"/api/stocks/stream", "/api/cryptos/stream"})
	void allowsPreflightForSseStreamsWithoutAuthentication(String streamPath) throws Exception {
		mockMvc.perform(options(streamPath)
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
				containsString(HttpHeaders.AUTHORIZATION)));
	}

	@Test
	void allowsPreflightForProtectedPathWithoutAuthentication() throws Exception {
		mockMvc.perform(options(PROTECTED_PATH)
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
			.andExpect(status().isOk());
	}

	@ParameterizedTest(name = "preflight {0}")
	@ValueSource(strings = {"/api/orders", "/api/orders/limit"})
	void allowsIdempotencyKeyHeaderInOrderPreflight(String orderPath) throws Exception {
		mockMvc.perform(options(orderPath)
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Idempotency-Key"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Idempotency-Key")));
	}

	@Test
	void allowsConfiguredMethodsInPreflight() throws Exception {
		mockMvc.perform(options("/api/community/posts/1")
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("DELETE")));
	}

	@Test
	void rejectsPreflightFromUnknownOrigin() throws Exception {
		mockMvc.perform(options("/api/stocks/stream")
			.header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void rejectsUnknownOriginOnActualRequest() throws Exception {
		mockMvc.perform(get(PUBLIC_PATH).header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@ParameterizedTest(name = "origin {0}")
	@ValueSource(strings = {ALLOWED_ORIGIN, ALLOWED_S3_ORIGIN})
	void allowsEveryConfiguredOrigin(String origin) throws Exception {
		mockMvc.perform(options("/api/stocks/stream")
			.header(HttpHeaders.ORIGIN, origin)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
	}

	@Test
	void exposesRequestIdHeaderToCrossOriginCallers() throws Exception {
		mockMvc.perform(get(PUBLIC_PATH).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
				containsString(RequestIdFilter.REQUEST_ID_HEADER)));
	}

	@Test
	void doesNotAllowCredentials() throws Exception {
		mockMvc.perform(options("/api/stocks/stream")
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void cachesPreflightSoRepeatedSseReconnectsDoNotPayForIt() throws Exception {
		mockMvc.perform(options("/api/cryptos/stream")
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
	}

	@Test
	void failsFastOnMalformedOrigins() {
		CorsConfig config = new CorsConfig();

		assertThatThrownBy(() -> config.corsConfigurationSource(List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("CORS_ALLOWED_ORIGINS");

		assertThatThrownBy(() -> config.corsConfigurationSource(List.of(ALLOWED_ORIGIN, "")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("빈 값");

		assertThatThrownBy(() -> config.corsConfigurationSource(List.of(ALLOWED_ORIGIN + "/")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("슬래시");

		assertThatThrownBy(() -> config.corsConfigurationSource(List.of("www.finplay.site")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("스킴");
	}

	@Test
	void trimsWhitespaceAroundCommaSeparatedOrigins() {
		CorsConfig config = new CorsConfig();

		UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource)config
			.corsConfigurationSource(List.of(" " + ALLOWED_ORIGIN + " "));

		assertThat(source.getCorsConfigurations().values())
			.singleElement()
			.extracting(CorsConfiguration::getAllowedOrigins)
			.isEqualTo(List.of(ALLOWED_ORIGIN));
	}

	@TestConfiguration
	static class TestTokenProviderConfig {

		@Bean
		JwtTokenProvider jwtTokenProvider() {
			return new JwtTokenProvider(
				"test-jwt-secret-that-is-at-least-32-bytes",
				3_600_000L,
				1_209_600_000L,
				Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));
		}
	}

	@RestController
	static class CorsTestController {

		@GetMapping(PUBLIC_PATH)
		Map<String, Object> corsProbe() {
			return Map.of("status", "UP");
		}
	}
}
