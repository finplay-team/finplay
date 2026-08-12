// preflight가 인증에 막히지 않고 허용 오리진만 통과하는지 검증하는 WebMvc 슬라이스 테스트다 (ADR-0022).
package com.finplay.api.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.RequestIdFilter;

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

	// 실제(비-preflight) 요청의 CORS 응답 헤더를 보려면 인증 없이 200이 나오는 경로가 필요하다.
	// SecurityConfig의 화이트리스트를 테스트 편의로 넓히지 않기 위해 이미 공개인 경로를 빌려 쓴다.
	private static final String PUBLIC_PATH = "/actuator/health";
	// preflight는 라우팅 전에 CorsFilter가 응답하므로 핸들러가 없어도 되고, 보호 경로여도 상관없다.
	private static final String PROTECTED_PATH = "/test/cors-protected";

	@Autowired
	private MockMvc mockMvc;

	// 이 테스트가 이번 변경의 핵심이다. 프론트는 SSE를 EventSource가 아니라 fetch + Authorization
	// 헤더로 연다(finplay-frontend useStockStream.ts). Authorization은 CORS 안전목록 헤더가 아니라
	// 스트림을 열기 전에 preflight가 반드시 선행하는데, 그 OPTIONS가 인증을 요구받으면 401로 끝나
	// SSE가 영영 열리지 않는다. 이슈 #108이 동일 오리진을 고른 유일한 유효 근거가 이 지점이다.
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
		// 여기서 401이 나오면 JwtAuthenticationFilter·인가 규칙이 OPTIONS까지 잡고 있다는 뜻이다.
		mockMvc.perform(options(PROTECTED_PATH)
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
			.andExpect(status().isOk());
	}

	@Test
	void allowsConfiguredMethodsInPreflight() throws Exception {
		mockMvc.perform(options("/api/community/posts/1")
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("DELETE")));
	}

	// 허용되는 쪽만 검증하면 설정이 전부 열려 있어도 통과한다 — 거부되는 쪽을 함께 고정한다.
	@Test
	void rejectsPreflightFromUnknownOrigin() throws Exception {
		mockMvc.perform(options("/api/stocks/stream")
			.header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	// 실제(비-preflight) 요청도 서버에서 403으로 끊긴다. "허용 헤더만 빼고 정상 처리한 뒤 브라우저가
	// 응답을 숨긴다"가 아니다 — CorsFilter가 핸들러 이전에 거부하므로 컨트롤러가 아예 실행되지 않는다.
	// 2026-08-12 실측으로 확인했고(그 전엔 반대로 알고 있었다), 부수 효과가 있는 요청이 실행된 뒤에
	// 응답만 가려지는 것이 아니라는 뜻이라 계약으로 고정할 가치가 있다.
	@Test
	void rejectsUnknownOriginOnActualRequest() throws Exception {
		mockMvc.perform(get(PUBLIC_PATH).header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	// A(S3 단독) → B(CloudFront) 전환기에 두 오리진을 함께 허용하는 것이 ADR-0022 §결정 3의 전제다.
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

	// 쿠키를 실어 보내지 않으므로 credentials를 허용하지 않는다. 이 헤더가 생기면 allowedOrigins에
	// "*"를 쓸 수 없게 되는 제약이 따라붙으므로 없는 상태를 고정한다 (ADR-0022 §결정 2).
	@Test
	void doesNotAllowCredentials() throws Exception {
		mockMvc.perform(options("/api/stocks/stream")
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	// 이슈 #108이 A안을 고른 근거 중 하나가 preflight 왕복 비용이었다. SSE는 재연결마다 그것을 다시
	// 치르므로 캐시 값을 계약으로 고정한다.
	@Test
	void cachesPreflightSoRepeatedSseReconnectsDoNotPayForIt() throws Exception {
		mockMvc.perform(options("/api/cryptos/stream")
			.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
	}

	// 오리진 형식 오류는 기동 시점에 드러나야 한다. 런타임까지 미루면 "앱은 멀쩡히 떴는데 브라우저에서만
	// 전 API가 막히는" 형태가 되어 원인을 CORS로 좁히는 데 시간이 걸린다.
	@Test
	void failsFastOnMalformedOrigins() {
		CorsConfig config = new CorsConfig();

		assertThatThrownBy(() -> config.corsConfigurationSource(List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("CORS_ALLOWED_ORIGINS");

		assertThatThrownBy(() -> config.corsConfigurationSource(List.of(ALLOWED_ORIGIN, "")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("빈 값");

		// 끝 슬래시는 흔한 오타이고, 붙으면 브라우저가 보내는 Origin과 문자열이 어긋나 조용히 막힌다.
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
