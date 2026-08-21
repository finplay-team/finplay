// server.forward-headers-strategy=framework이 활성화하는 ForwardedHeaderFilter가 ALB의 X-Forwarded-Proto를
// 실제로 해석하는지 검증하는 슬라이스 테스트다 (ADR-0022, PR #353 리뷰 권장 2번).
package com.finplay.api.domain.auth.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * server.forward-headers-strategy=framework을 켜면 Spring Boot가 {@code ServletWebServerConfiguration}
 * (spring-boot-web-server 모듈)에서 {@link ForwardedHeaderFilter}를 {@code FilterRegistrationBean}으로
 * 등록한다 — 실제 임베디드 서버가 있어야 도는 자동설정이라 {@code @WebMvcTest} 슬라이스에는 로드되지
 * 않는다(실측 확인: {@code @TestPropertySource}만으로는 이 필터가 안 걸린다). 그래서 그 자동설정을
 * 재현하지 않고, ALB가 활성화할 바로 그 필터 클래스를 이 슬라이스에 직접 등록해 동작만 검증한다.
 * "이 프로퍼티가 이 필터를 켠다"는 Spring Boot 자체가 보장하는 부분이라 다시 테스트하지 않는다.
 */
// /test/**는 프로덕션 화이트리스트에 없고 이 테스트는 인증 여부가 아니라 프로토콜 해석만 보므로,
// GlobalExceptionHandlerTest와 같은 방식으로 Security 자동설정을 끈다.
@WebMvcTest(controllers = ForwardedHeaderStrategyTest.SchemeProbeController.class, excludeAutoConfiguration = {
	ServletWebSecurityAutoConfiguration.class,
	SecurityFilterAutoConfiguration.class
})
@Import({
	ForwardedHeaderStrategyTest.SchemeProbeController.class,
	ForwardedHeaderStrategyTest.ForwardedHeaderFilterTestConfig.class
})
class ForwardedHeaderStrategyTest {

	private static final String PROBE_PATH = "/test/scheme-probe";

	@Autowired
	private MockMvc mockMvc;

	// ADR-0022가 이 설정이 빠지면 OAuth 리다이렉트 URL 생성·Secure 쿠키 발급이 틀어질 수 있다고 적은
	// 그 경로를 직접 확인한다. nginx가 지금까지 X-Forwarded-Proto: https를 강제 주입해 온 것과 같은
	// 헤더를 ALB가 대신 보낸다고 가정하고, Spring이 그것을 실제로 https 요청으로 해석하는지 본다.
	@Test
	void treatsRequestAsSecureWhenAlbForwardsHttpsProto() throws Exception {
		mockMvc.perform(get(PROBE_PATH)
			.header("X-Forwarded-Proto", "https")
			.header("X-Forwarded-Host", "finplay.site"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.secure").value(true))
			.andExpect(jsonPath("$.scheme").value("https"));
	}

	// 헤더가 없는 평범한 요청(로컬 dev 등)까지 강제로 secure 취급하면 안 된다 — 이 필터가
	// "항상 https로 우긴다"가 아니라 "전달받은 값을 그대로 반영한다"는 것을 함께 고정한다.
	@Test
	void doesNotForceSecureWithoutForwardedHeader() throws Exception {
		mockMvc.perform(get(PROBE_PATH))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.secure").value(false))
			.andExpect(jsonPath("$.scheme").value("http"));
	}

	@TestConfiguration
	static class ForwardedHeaderFilterTestConfig {

		@Bean
		FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
			FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(
				new ForwardedHeaderFilter());
			registration.setDispatcherTypes(DispatcherType.REQUEST);
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}
	}

	@RestController
	static class SchemeProbeController {

		@GetMapping(PROBE_PATH)
		Map<String, Object> probe(HttpServletRequest request) {
			return Map.of("secure", request.isSecure(), "scheme", request.getScheme());
		}
	}
}
