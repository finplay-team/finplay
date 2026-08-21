// 프론트(S3)와 API의 오리진이 갈라지면서 필요해진 CORS 허용 정책을 정의한다 (ADR-0022)
package com.finplay.api.domain.auth.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.finplay.api.global.filter.RequestIdFilter;

@Configuration
public class CorsConfig {

	// 프론트는 /api만 호출하지만 경로를 좁히지 않는다. 좁히면 나중에 추가되는 경로가 조용히 CORS에서
	// 빠져 "이 엔드포인트만 브라우저에서 안 된다"는 형태로 드러난다. 오리진 자체가 화이트리스트이고
	// allowCredentials가 false이므로 경로를 넓게 두는 것이 권한을 넓히지 않는다.
	private static final String ALL_PATHS = "/**";

	private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

	// 주문 생성 API(POST /api/orders, POST /api/orders/limit)가 멱등성 판정에 쓰는 커스텀 헤더 — CORS
	// 안전목록이 아니므로 여기서 허용하지 않으면 preflight 단계에서 막혀 주식·코인 주문이 전부 CORS 오류로 실패한다.
	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	// preflight 응답을 브라우저가 1시간 캐싱한다. 이슈 #108이 A안을 고른 근거 중 하나가 "preflight 왕복
	// 비용"이었고, SSE는 재연결마다 이 왕복을 다시 치른다 — 캐시가 그 비용을 실질적으로 없앤다.
	private static final long PREFLIGHT_CACHE_SECONDS = 3600L;

	@Bean
	public CorsConfigurationSource corsConfigurationSource(
		// 생성자 주입을 쓰지 않는다. 이 저장소에서 @Value 필드 + Lombok 생성자는 주입이 조용히 실패했고
		// (agent-mistakes 2026-07-30), 손으로 쓴 생성자는 SpotBugs EI_EXPOSE_REP2를 부른 전례가 있다
		// (2026-07-29). @Bean 메서드 파라미터는 둘 다 해당하지 않는다.
		@Value("${finplay.cors.allowed-origins}")
		List<String> allowedOrigins) {

		List<String> origins = validate(allowedOrigins);

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(origins);
		configuration.setAllowedMethods(ALLOWED_METHODS);
		// SSE(/api/stocks/stream, /api/cryptos/stream)가 Authorization 헤더를 싣는다. 이 헤더는 CORS
		// 안전목록이 아니므로 여기서 허용하지 않으면 preflight 단계에서 막혀 스트림이 열리지 않는다.
		configuration.setAllowedHeaders(
			List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, IDEMPOTENCY_KEY_HEADER));
		// 크로스 오리진에서는 응답 헤더가 기본적으로 안 보인다. 장애 추적을 위해 요청 식별자만 연다.
		configuration.setExposedHeaders(List.of(RequestIdFilter.REQUEST_ID_HEADER));
		// 토큰을 localStorage + Bearer 헤더로 전달하므로 쿠키를 실을 일이 없다 (ADR-0022 §결정 2).
		// true로 바꾸려면 allowedOrigins에 "*"를 쓸 수 없게 되는 제약이 함께 따라온다.
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(PREFLIGHT_CACHE_SECONDS);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration(ALL_PATHS, configuration);
		return source;
	}

	// 오리진이 틀리면 앱은 정상 기동하고 브라우저에서만 전 API가 막힌다 — "화면은 뜨는데 아무것도 안 되는"
	// 형태라 원인을 CORS로 좁히기까지 시간이 걸린다. 값의 형식 오류는 기동 시점에 드러나게 한다.
	// (REDIS_HOST에 기본값을 두지 않는 것과 같은 판단이다 — application-prod.yml 주석 참고)
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
