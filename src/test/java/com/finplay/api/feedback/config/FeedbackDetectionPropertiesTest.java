// feedback.detection.* 프로퍼티가 설정 없이도 spec 012 §C-7 기본값으로 바인딩되는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// FeedbackLlmPropertiesTest와 같은 짝의 앞쪽이다 — 여기서는 record의 @DefaultValue만 본다.
// application.yml 쪽 키 경로는 FeedbackDetectionPropertiesYamlTest가 맡는다.
//
// 기대값의 정본은 ai/specs/012-ai-feedback/spec.md §C-7이다. 구현 파일이 아니라 spec에서 값을
// 가져와야 record와 yml이 함께 틀어지는 드리프트가 잡힌다.
class FeedbackDetectionPropertiesTest {

	// §C-7 feedback.detection 블록
	private static final double SPEC_Z_SCORE_K = 2.5;

	private static final int SPEC_WINDOW_MINUTES = 5;

	private static final int SPEC_MERGE_WINDOW_MINUTES = 5;

	private static final int SPEC_MAX_INTRADAY_CARDS = 2;

	private static final BigDecimal SPEC_OPENING_GAP_THRESHOLD = new BigDecimal("0.01");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(FeedbackDetectionConfig.class);

	@Test
	@DisplayName("feedback.detection 설정을 하나도 주지 않아도 §C-7 기본값으로 바인딩된다")
	void bindsSpecDefaultsWhenNoFeedbackDetectionPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackDetectionProperties.class);

			FeedbackDetectionProperties properties = context.getBean(FeedbackDetectionProperties.class);
			assertThat(properties.zScoreK()).isEqualTo(SPEC_Z_SCORE_K);
			assertThat(properties.windowMinutes()).isEqualTo(SPEC_WINDOW_MINUTES);
			assertThat(properties.mergeWindowMinutes()).isEqualTo(SPEC_MERGE_WINDOW_MINUTES);
			assertThat(properties.maxIntradayCards()).isEqualTo(SPEC_MAX_INTRADAY_CARDS);
			assertThat(properties.openingGapThreshold())
				.isEqualByComparingTo(SPEC_OPENING_GAP_THRESHOLD);
		});
	}

	@Test
	@DisplayName("feedback.detection.* 케밥케이스 키를 주면 다섯 값이 모두 덮어써진다")
	void bindsEveryPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.detection.z-score-k=3.0",
				"feedback.detection.window-minutes=10",
				"feedback.detection.merge-window-minutes=7",
				"feedback.detection.max-intraday-cards=4",
				"feedback.detection.opening-gap-threshold=0.02")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackDetectionProperties properties = context.getBean(FeedbackDetectionProperties.class);
				assertThat(properties.zScoreK()).isEqualTo(3.0);
				assertThat(properties.windowMinutes()).isEqualTo(10);
				assertThat(properties.mergeWindowMinutes()).isEqualTo(7);
				assertThat(properties.maxIntradayCards()).isEqualTo(4);
				assertThat(properties.openingGapThreshold())
					.isEqualByComparingTo(new BigDecimal("0.02"));
			});
	}

	@Test
	@DisplayName("일부 값만 덮어써도 나머지는 §C-7 기본값을 유지한다")
	void keepsSpecDefaultsForPropertiesThatAreNotGiven() {
		contextRunner
			.withPropertyValues("feedback.detection.z-score-k=1.8")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackDetectionProperties properties = context.getBean(FeedbackDetectionProperties.class);
				assertThat(properties.zScoreK()).isEqualTo(1.8);
				assertThat(properties.windowMinutes()).isEqualTo(SPEC_WINDOW_MINUTES);
				assertThat(properties.mergeWindowMinutes()).isEqualTo(SPEC_MERGE_WINDOW_MINUTES);
				assertThat(properties.maxIntradayCards()).isEqualTo(SPEC_MAX_INTRADAY_CARDS);
				assertThat(properties.openingGapThreshold())
					.isEqualByComparingTo(SPEC_OPENING_GAP_THRESHOLD);
			});
	}

	@Test
	@DisplayName("숫자 항목에 숫자가 아닌 값이 오면 기동이 실패한다 — 0으로 조용히 넘어가지 않는다")
	void failsFastWhenNumericPropertyIsNotANumber() {
		contextRunner
			.withPropertyValues("feedback.detection.window-minutes=five")
			.run(context -> assertThat(context).hasFailed());
	}

	// 아래 셋은 예외도 로그도 없이 결과가 사라지거나 배치를 죽이는 값이라 record가 기동 시점에 막는다.
	// window-minutes=0이면 cum이 전부 0이라 장중 카드가 매일 0건이 되고, max-intraday-cards=0이면
	// 후보를 아무리 찾아도 채택이 0건이며, opening-gap-threshold=0은 detectionScore의 분모다.
	@Test
	@DisplayName("window-minutes가 1 미만이면 기동이 실패한다")
	void failsWhenWindowMinutesIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.detection.window-minutes=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("window-minutes"));
	}

	@Test
	@DisplayName("max-intraday-cards가 1 미만이면 기동이 실패한다")
	void failsWhenMaxIntradayCardsIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.detection.max-intraday-cards=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-intraday-cards"));
	}

	@Test
	@DisplayName("opening-gap-threshold가 0 이하이면 기동이 실패한다")
	void failsWhenOpeningGapThresholdIsNotPositive() {
		contextRunner
			.withPropertyValues("feedback.detection.opening-gap-threshold=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("opening-gap-threshold"));

		contextRunner
			.withPropertyValues("feedback.detection.opening-gap-threshold=-0.01")
			.run(context -> assertThat(context).hasFailed());
	}
}
