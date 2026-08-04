// application.yml의 feedback.detection 블록이 spec 012 §C-7의 키 경로·값 그대로 존재하는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// FeedbackDetectionPropertiesTest는 record의 @DefaultValue만 보므로, application.yml의 키가 잘못된
// 위치·이름으로 들어가도 기본값에 가려 통과한다. §C-7은 "운영 중 값을 바꿀 때는 항상 이기는 yml만
// 고친다"를 확정했으므로 yml 쪽 키 경로가 실제로 그 경로인지도 단정해야 드리프트가 잡힌다.
//
// FeedbackLlmPropertiesIntegrationTest와 의도는 같지만 @SpringBootTest·Testcontainers를 쓰지 않는다.
// 이 항목이 검증하는 것은 "yml 파일의 키 경로와 값"뿐이라 DB·자동설정이 필요 없고, 컨테이너를 띄우면
// Docker가 없는 환경에서 설정 드리프트를 못 보게 된다. ConfigDataApplicationContextInitializer가
// SpringApplication 부트스트랩과 같은 방식으로 application.yml만 Environment에 얹어 준다.
class FeedbackDetectionPropertiesYamlTest {

	// §C-7 feedback.detection 블록. 문자열로 두는 것은 yml에 적힌 표기 그대로를 비교하기 위해서다.
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

	// yml이 항상 이기므로 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다.
	// 여기서 바인딩된 빈이 §C-7 값과 같은지까지 봐야 그 상태가 드러난다.
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
