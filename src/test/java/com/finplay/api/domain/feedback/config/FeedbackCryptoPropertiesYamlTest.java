package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.config.MarketCryptoConfig;
import com.finplay.api.domain.market.config.MarketCryptoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class FeedbackCryptoPropertiesYamlTest {

	private static final String SPEC_COOLDOWN_MINUTES = "30";

	private static final String SPEC_DAILY_LIMIT = "6";

	private static final String SPEC_ROLLING_WINDOW_MINUTES = "5";

	private static final String SPEC_SIGMA_LOOKBACK_HOURS = "24";

	private static final String SPEC_MIN_SAMPLE_COUNT = "100";

	private static final String SPEC_MATCH_BEFORE_MINUTES = "35";

	private static final String SPEC_WATCH_LOCK_TTL_SECONDS = "45";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(
			FeedbackCryptoPropertiesTest.LocalFeedbackCryptoConfig.class, MarketCryptoConfig.class);

	@Test
	@DisplayName("application.yml에 feedback.crypto 일곱 키가 §C-7 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackCryptoKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("feedback.crypto.cooldown-minutes"))
				.isEqualTo(SPEC_COOLDOWN_MINUTES);
			assertThat(environment.getProperty("feedback.crypto.daily-limit"))
				.isEqualTo(SPEC_DAILY_LIMIT);
			assertThat(environment.getProperty("feedback.crypto.rolling-window-minutes"))
				.isEqualTo(SPEC_ROLLING_WINDOW_MINUTES);
			assertThat(environment.getProperty("feedback.crypto.sigma-lookback-hours"))
				.isEqualTo(SPEC_SIGMA_LOOKBACK_HOURS);
			assertThat(environment.getProperty("feedback.crypto.min-sample-count"))
				.isEqualTo(SPEC_MIN_SAMPLE_COUNT);
			assertThat(environment.getProperty("feedback.crypto.match-before-minutes"))
				.isEqualTo(SPEC_MATCH_BEFORE_MINUTES);
			assertThat(environment.getProperty("feedback.crypto.watch-lock-ttl-seconds"))
				.isEqualTo(SPEC_WATCH_LOCK_TTL_SECONDS);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 §C-7 값을 갖는다")
	void boundBeanMatchesSpecValuesWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackCryptoProperties properties = context.getBean(FeedbackCryptoProperties.class);
			assertThat(properties.cooldownMinutes()).isEqualTo(Integer.parseInt(SPEC_COOLDOWN_MINUTES));
			assertThat(properties.dailyLimit()).isEqualTo(Integer.parseInt(SPEC_DAILY_LIMIT));
			assertThat(properties.rollingWindowMinutes())
				.isEqualTo(Integer.parseInt(SPEC_ROLLING_WINDOW_MINUTES));
			assertThat(properties.sigmaLookbackHours())
				.isEqualTo(Integer.parseInt(SPEC_SIGMA_LOOKBACK_HOURS));
			assertThat(properties.minSampleCount()).isEqualTo(Integer.parseInt(SPEC_MIN_SAMPLE_COUNT));
			assertThat(properties.matchBeforeMinutes())
				.isEqualTo(Integer.parseInt(SPEC_MATCH_BEFORE_MINUTES));
			assertThat(properties.watchLockTtlSeconds())
				.isEqualTo(Integer.parseInt(SPEC_WATCH_LOCK_TTL_SECONDS));
		});
	}

	@Test
	@DisplayName("market.crypto.sigma-lookback-hours와 feedback.crypto.sigma-lookback-hours가 같다")
	void marketAndFeedbackSigmaLookbackHoursStayInSync() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			int feedbackValue = context.getBean(FeedbackCryptoProperties.class).sigmaLookbackHours();
			int marketValue = context.getBean(MarketCryptoProperties.class).sigmaLookbackHours();

			assertThat(feedbackValue).isEqualTo(marketValue);
			assertThat(feedbackValue).isEqualTo(Integer.parseInt(SPEC_SIGMA_LOOKBACK_HOURS));
		});
	}
}
