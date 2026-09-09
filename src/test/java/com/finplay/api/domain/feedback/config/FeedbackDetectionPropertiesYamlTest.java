package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class FeedbackDetectionPropertiesYamlTest {

	private static final String SPEC_Z_SCORE_K = "2.5";

	private static final String SPEC_WINDOW_MINUTES = "5";

	private static final String SPEC_MERGE_WINDOW_MINUTES = "5";

	private static final String SPEC_MAX_INTRADAY_CARDS = "2";

	private static final String SPEC_OPENING_GAP_THRESHOLD = "0.01";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(FeedbackDetectionConfig.class);

	@Test
	@DisplayName("application.yml에 feedback.detection 다섯 키가 §C-7 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackDetectionKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("feedback.detection.z-score-k"))
				.isEqualTo(SPEC_Z_SCORE_K);
			assertThat(environment.getProperty("feedback.detection.window-minutes"))
				.isEqualTo(SPEC_WINDOW_MINUTES);
			assertThat(environment.getProperty("feedback.detection.merge-window-minutes"))
				.isEqualTo(SPEC_MERGE_WINDOW_MINUTES);
			assertThat(environment.getProperty("feedback.detection.max-intraday-cards"))
				.isEqualTo(SPEC_MAX_INTRADAY_CARDS);
			assertThat(environment.getProperty("feedback.detection.opening-gap-threshold"))
				.isEqualTo(SPEC_OPENING_GAP_THRESHOLD);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 §C-7 값을 갖는다")
	void boundBeanMatchesSpecValuesWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackDetectionProperties properties = context.getBean(FeedbackDetectionProperties.class);
			assertThat(properties.zScoreK()).isEqualTo(Double.parseDouble(SPEC_Z_SCORE_K));
			assertThat(properties.windowMinutes()).isEqualTo(Integer.parseInt(SPEC_WINDOW_MINUTES));
			assertThat(properties.mergeWindowMinutes())
				.isEqualTo(Integer.parseInt(SPEC_MERGE_WINDOW_MINUTES));
			assertThat(properties.maxIntradayCards())
				.isEqualTo(Integer.parseInt(SPEC_MAX_INTRADAY_CARDS));
			assertThat(properties.openingGapThreshold())
				.isEqualByComparingTo(new BigDecimal(SPEC_OPENING_GAP_THRESHOLD));
		});
	}
}
