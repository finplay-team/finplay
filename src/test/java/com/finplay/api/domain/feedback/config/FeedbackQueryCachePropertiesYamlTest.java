package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class FeedbackQueryCachePropertiesYamlTest {

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
