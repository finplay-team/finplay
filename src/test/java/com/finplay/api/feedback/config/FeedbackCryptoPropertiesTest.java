// feedback.crypto.* 프로퍼티가 설정 없이도 spec 012 §C-7 기본값으로 바인딩되는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// FeedbackDetectionPropertiesTest와 같은 짝의 앞쪽이다 — 여기서는 record의 @DefaultValue만 본다.
// application.yml 쪽 키 경로는 FeedbackCryptoPropertiesYamlTest가 맡는다.
//
// 기대값의 정본은 ai/specs/012-ai-feedback/spec.md §C-7이다. 구현 파일이 아니라 spec에서 값을
// 가져와야 record와 yml이 함께 틀어지는 드리프트가 잡힌다.
//
// 주의 — FeedbackDetectionConfig·FeedbackLlmConfig와 달리 FeedbackCryptoProperties를
// @EnableConfigurationProperties로 등록하는 production 설정 클래스(FeedbackCryptoConfig 등)가 src/main에
// 없다. NewsMatcher는 생성자로 FeedbackCryptoProperties를 주입받는데, 이 빈이 어디서도 등록되지 않으면
// 실제 애플리케이션 컨텍스트 기동 시 NewsMatcher를 만들 수 없어 NoSuchBeanDefinitionException으로 죌
// 것으로 보인다(FinPlayApiApplicationTests 스모크 테스트가 이를 드러낸다). 이 파일은 record 자체의
// 바인딩 규칙만 보려고 로컬 테스트 전용 설정으로 빈을 임시 등록한다 — production 등록 여부는 이 테스트가
// 보증하지 않는다.
class FeedbackCryptoPropertiesTest {

	// §C-7 feedback.crypto 블록
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

	// 아래 다섯은 예외도 로그도 없이(watch-lock-ttl-seconds는 DEBUG 로그 한 줄만 남기고) 카드가 조용히
	// 사라지는 값이라 record가 기동 시점에 막는다(FeedbackCryptoProperties의 검증 블록 주석 참조).
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

	// 0 이하면 Duration.ofSeconds가 Redis 명령 오류를 유발하고 CryptoWatchLock.tryLock의
	// catch(RuntimeException)이 이를 삼켜 항상 Optional.empty()를 반환한다 — 모든 코인 카드가 DEBUG 로그
	// 한 줄만 남기고 영구 0건이 된다(이슈 #244 2차 리뷰 [권장 2]).
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
