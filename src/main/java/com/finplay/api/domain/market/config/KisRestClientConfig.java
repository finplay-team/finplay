// KIS Open API REST 호출(과거 분봉 조회·토큰 발급)에 유한 연결·응답 timeout을 적용한 RestClient를 완성해 빈으로 등록한다.
package com.finplay.api.domain.market.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// @Bean 메서드가 하나뿐이라 self-invocation이 없으므로 CGLIB 프록시(proxyBeanMethods)가 필요 없다.
// KisProperties(kis.*)를 여기서 활성화한다 — record 기반 @ConfigurationProperties는 명시적으로 등록해야 빈이 된다.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KisProperties.class)
public class KisRestClientConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	// 타임아웃이 적용된 완성된 RestClient를 그대로 주입받게 한다 — KisHistoricalCandleClientImpl이
	// 파생 로직 없는 파라미터 직접 대입만으로 @RequiredArgsConstructor를 쓸 수 있도록(SpotBugs EI_EXPOSE_REP2
	// 회피, ai/agent-mistakes.md 2026-07-29 항목 참고) 빌드 로직을 이 클래스가 담당한다.
	//
	// RestClient.Builder를 DI로 받지 않고 RestClient.builder()를 직접 호출하는 이유는 ADR-0023 참고
	// (BithumbRestTickerPoller와 동일한 Jackson 2/3 공존 위험, PR #377 리뷰 권장①).
	@Bean
	public RestClient kisRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder().requestFactory(requestFactory).build();
	}
}
