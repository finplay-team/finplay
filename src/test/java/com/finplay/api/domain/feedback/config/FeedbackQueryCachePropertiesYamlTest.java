// application.yml의 feedback.query-cache 블록이 ADR-0015의 키 경로·값 그대로 존재하는지 검증한다.
package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// FeedbackCryptoPropertiesYamlTest와 같은 짝이다 — FeedbackQueryCachePropertiesTest는 record의 @DefaultValue만
// 보므로, application.yml의 키가 잘못된 위치·이름으로 들어가도 기본값에 가려 통과한다. yml이 항상 이기므로
// 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다 — 특히 enabled는 운영
// 킬 스위치라, 키 경로가 틀리면 "false로 내렸는데 캐시가 계속 켜져 있는" 상태가 조용히 생긴다.
//
// ConfigDataApplicationContextInitializer가 SpringApplication 부트스트랩과 같은 방식으로 application.yml만
// Environment에 얹어 준다 — Docker 없는 환경에서도 돈다.
class FeedbackQueryCachePropertiesYamlTest {

	// ADR-0015. 문자열로 두는 것은 yml에 적힌 표기 그대로를 비교하기 위해서다.
	private static final String ADR_ENABLED = "true";

	private static final String ADR_LOCK_TTL_MILLIS = "1000";

	private static final String ADR_WAIT_MILLIS = "300";

	private static final String ADR_POLL_MILLIS = "20";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(FeedbackQueryCacheConfig.class);

	@Test
	@DisplayName("application.yml에 feedback.query-cache 네 키가 ADR-0015 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackQueryCacheKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("feedback.query-cache.enabled")).isEqualTo(ADR_ENABLED);
			assertThat(environment.getProperty("feedback.query-cache.lock-ttl-millis"))
				.isEqualTo(ADR_LOCK_TTL_MILLIS);
			assertThat(environment.getProperty("feedback.query-cache.wait-millis")).isEqualTo(ADR_WAIT_MILLIS);
			assertThat(environment.getProperty("feedback.query-cache.poll-millis")).isEqualTo(ADR_POLL_MILLIS);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 ADR-0015 값을 갖는다")
	void boundBeanMatchesAdrValuesWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackQueryCacheProperties properties = context.getBean(FeedbackQueryCacheProperties.class);
			assertThat(properties.enabled()).isEqualTo(Boolean.parseBoolean(ADR_ENABLED));
			assertThat(properties.lockTtlMillis()).isEqualTo(Long.parseLong(ADR_LOCK_TTL_MILLIS));
			assertThat(properties.waitMillis()).isEqualTo(Long.parseLong(ADR_WAIT_MILLIS));
			assertThat(properties.pollMillis()).isEqualTo(Long.parseLong(ADR_POLL_MILLIS));
		});
	}
}
