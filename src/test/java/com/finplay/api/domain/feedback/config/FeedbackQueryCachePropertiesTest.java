package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FeedbackQueryCachePropertiesTest {

	private static final boolean ADR_ENABLED = true;

	private static final long ADR_LOCK_TTL_MILLIS = 1000;

	private static final long ADR_WAIT_MILLIS = 300;

	private static final long ADR_POLL_MILLIS = 20;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(FeedbackQueryCacheConfig.class);

	@Test
	@DisplayName("feedback.query-cache 설정을 하나도 주지 않아도 ADR-0015 기본값으로 바인딩된다")
	void bindsAdrDefaultsWhenNoFeedbackQueryCachePropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackQueryCacheProperties.class);

			FeedbackQueryCacheProperties properties = context.getBean(FeedbackQueryCacheProperties.class);
			assertThat(properties.enabled()).isEqualTo(ADR_ENABLED);
			assertThat(properties.lockTtlMillis()).isEqualTo(ADR_LOCK_TTL_MILLIS);
			assertThat(properties.waitMillis()).isEqualTo(ADR_WAIT_MILLIS);
			assertThat(properties.pollMillis()).isEqualTo(ADR_POLL_MILLIS);
		});
	}

	@Test
	@DisplayName("feedback.query-cache.* 케밥케이스 키를 주면 네 값이 모두 덮어써진다")
	void bindsEveryPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.query-cache.enabled=false",
				"feedback.query-cache.lock-ttl-millis=2000",
				"feedback.query-cache.wait-millis=500",
				"feedback.query-cache.poll-millis=50")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackQueryCacheProperties properties = context.getBean(FeedbackQueryCacheProperties.class);
				assertThat(properties.enabled()).isFalse();
				assertThat(properties.lockTtlMillis()).isEqualTo(2000);
				assertThat(properties.waitMillis()).isEqualTo(500);
				assertThat(properties.pollMillis()).isEqualTo(50);
			});
	}

	@Test
	@DisplayName("일부 값만 덮어써도 나머지는 ADR-0015 기본값을 유지한다")
	void keepsAdrDefaultsForPropertiesThatAreNotGiven() {
		contextRunner
			.withPropertyValues("feedback.query-cache.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackQueryCacheProperties properties = context.getBean(FeedbackQueryCacheProperties.class);
				assertThat(properties.enabled()).isFalse();
				assertThat(properties.lockTtlMillis()).isEqualTo(ADR_LOCK_TTL_MILLIS);
				assertThat(properties.waitMillis()).isEqualTo(ADR_WAIT_MILLIS);
				assertThat(properties.pollMillis()).isEqualTo(ADR_POLL_MILLIS);
			});
	}

	@Test
	@DisplayName("lock-ttl-millis가 1 미만이면 기동이 실패한다")
	void failsWhenLockTtlMillisIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.query-cache.lock-ttl-millis=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lock-ttl-millis"));
	}

	@Test
	@DisplayName("wait-millis가 음수이면 기동이 실패한다")
	void failsWhenWaitMillisIsNegative() {
		contextRunner
			.withPropertyValues("feedback.query-cache.wait-millis=-1")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("wait-millis"));
	}

	@Test
	@DisplayName("wait-millis가 0이면 유효한 값으로 바인딩된다")
	void acceptsZeroWaitMillisBecauseItMeansGoingStraightToTheLoader() {
		contextRunner
			.withPropertyValues("feedback.query-cache.wait-millis=0")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(FeedbackQueryCacheProperties.class).waitMillis()).isZero();
			});
	}

	@Test
	@DisplayName("poll-millis가 1 미만이면 기동이 실패한다")
	void failsWhenPollMillisIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.query-cache.poll-millis=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("poll-millis"));
	}
}
