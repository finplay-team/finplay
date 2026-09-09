package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class FeedbackCryptoPropertiesTest {

	private static final int SPEC_COOLDOWN_MINUTES = 30;

	private static final int SPEC_DAILY_LIMIT = 6;

	private static final int SPEC_ROLLING_WINDOW_MINUTES = 5;

	private static final int SPEC_SIGMA_LOOKBACK_HOURS = 24;

	private static final int SPEC_MIN_SAMPLE_COUNT = 100;

	private static final int SPEC_MATCH_BEFORE_MINUTES = 35;

	private static final int SPEC_WATCH_LOCK_TTL_SECONDS = 45;

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(FeedbackCryptoProperties.class)
	static class LocalFeedbackCryptoConfig {}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(LocalFeedbackCryptoConfig.class);

	@Test
	@DisplayName("feedback.crypto 설정을 하나도 주지 않아도 §C-7 기본값으로 바인딩된다")
	void bindsSpecDefaultsWhenNoFeedbackCryptoPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackCryptoProperties.class);

			FeedbackCryptoProperties properties = context.getBean(FeedbackCryptoProperties.class);
			assertThat(properties.cooldownMinutes()).isEqualTo(SPEC_COOLDOWN_MINUTES);
			assertThat(properties.dailyLimit()).isEqualTo(SPEC_DAILY_LIMIT);
			assertThat(properties.rollingWindowMinutes()).isEqualTo(SPEC_ROLLING_WINDOW_MINUTES);
			assertThat(properties.sigmaLookbackHours()).isEqualTo(SPEC_SIGMA_LOOKBACK_HOURS);
			assertThat(properties.minSampleCount()).isEqualTo(SPEC_MIN_SAMPLE_COUNT);
			assertThat(properties.matchBeforeMinutes()).isEqualTo(SPEC_MATCH_BEFORE_MINUTES);
			assertThat(properties.watchLockTtlSeconds()).isEqualTo(SPEC_WATCH_LOCK_TTL_SECONDS);
		});
	}

	@Test
	@DisplayName("feedback.crypto.* 케밥케이스 키를 주면 일곱 값이 모두 덮어써진다")
	void bindsEveryPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.crypto.cooldown-minutes=15",
				"feedback.crypto.daily-limit=3",
				"feedback.crypto.rolling-window-minutes=10",
				"feedback.crypto.sigma-lookback-hours=12",
				"feedback.crypto.min-sample-count=50",
				"feedback.crypto.match-before-minutes=20",
				"feedback.crypto.watch-lock-ttl-seconds=60")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackCryptoProperties properties = context.getBean(FeedbackCryptoProperties.class);
				assertThat(properties.cooldownMinutes()).isEqualTo(15);
				assertThat(properties.dailyLimit()).isEqualTo(3);
				assertThat(properties.rollingWindowMinutes()).isEqualTo(10);
				assertThat(properties.sigmaLookbackHours()).isEqualTo(12);
				assertThat(properties.minSampleCount()).isEqualTo(50);
				assertThat(properties.matchBeforeMinutes()).isEqualTo(20);
				assertThat(properties.watchLockTtlSeconds()).isEqualTo(60);
			});
	}

	@Test
	@DisplayName("일부 값만 덮어써도 나머지는 §C-7 기본값을 유지한다")
	void keepsSpecDefaultsForPropertiesThatAreNotGiven() {
		contextRunner
			.withPropertyValues("feedback.crypto.daily-limit=10")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackCryptoProperties properties = context.getBean(FeedbackCryptoProperties.class);
				assertThat(properties.dailyLimit()).isEqualTo(10);
				assertThat(properties.cooldownMinutes()).isEqualTo(SPEC_COOLDOWN_MINUTES);
				assertThat(properties.rollingWindowMinutes()).isEqualTo(SPEC_ROLLING_WINDOW_MINUTES);
				assertThat(properties.sigmaLookbackHours()).isEqualTo(SPEC_SIGMA_LOOKBACK_HOURS);
				assertThat(properties.minSampleCount()).isEqualTo(SPEC_MIN_SAMPLE_COUNT);
				assertThat(properties.matchBeforeMinutes()).isEqualTo(SPEC_MATCH_BEFORE_MINUTES);
				assertThat(properties.watchLockTtlSeconds()).isEqualTo(SPEC_WATCH_LOCK_TTL_SECONDS);
			});
	}

	@Test
	@DisplayName("rolling-window-minutes가 1 미만이면 기동이 실패한다")
	void failsWhenRollingWindowMinutesIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.crypto.rolling-window-minutes=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("rolling-window-minutes"));
	}

	@Test
	@DisplayName("sigma-lookback-hours가 1 미만이면 기동이 실패한다")
	void failsWhenSigmaLookbackHoursIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.crypto.sigma-lookback-hours=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sigma-lookback-hours"));
	}

	@Test
	@DisplayName("min-sample-count가 1 미만이면 기동이 실패한다")
	void failsWhenMinSampleCountIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.crypto.min-sample-count=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("min-sample-count"));
	}

	@Test
	@DisplayName("match-before-minutes가 음수이면 기동이 실패한다")
	void failsWhenMatchBeforeMinutesIsNegative() {
		contextRunner
			.withPropertyValues("feedback.crypto.match-before-minutes=-1")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("match-before-minutes"));
	}

	@Test
	@DisplayName("watch-lock-ttl-seconds가 1 미만이면 기동이 실패한다")
	void failsWhenWatchLockTtlSecondsIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.crypto.watch-lock-ttl-seconds=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("watch-lock-ttl-seconds"));
	}
}
