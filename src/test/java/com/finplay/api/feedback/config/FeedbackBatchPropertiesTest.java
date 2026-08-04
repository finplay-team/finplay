// feedback.batch.* 프로퍼티가 spec 012 §C-1 값으로 바인딩되고 application.yml과 갈리지 않는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;

// §C-7이 확정한 방침대로 yml과 record @DefaultValue 양쪽에 값이 있으므로 두 곳이 갈리는지 대조한다
// (FeedbackDetectionPropertiesTest·NewsCollectionPropertiesTest와 같은 형태).
//
// 실제로 스케줄을 결정하는 것은 @Scheduled가 읽는 Environment 쪽이라 yml 값이 항상 이긴다 — record의
// @DefaultValue만 보면 yml 키가 잘못된 위치·이름으로 들어가도 기본값에 가려 통과한다. 그래서 yml 쪽
// 키 경로도 함께 단정하되, 이 항목에는 DB가 필요 없으므로 컨테이너를 띄우지 않고
// ConfigDataApplicationContextInitializer로 application.yml만 Environment에 얹는다.
//
// 기대값의 정본은 spec.md §C-1이다.
class FeedbackBatchPropertiesTest {

	// §C-1 개장 전 배치 크론
	private static final String SPEC_BATCH_CRON = "0 45 8 * * MON-FRI";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(FeedbackBatchConfig.class);

	@Test
	@DisplayName("feedback.batch 설정을 주지 않아도 §C-1 크론으로 바인딩된다")
	void bindsSpecCronDefaultWhenNoFeedbackBatchPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackBatchProperties.class);
			assertThat(context.getBean(FeedbackBatchProperties.class).cron()).isEqualTo(SPEC_BATCH_CRON);
		});
	}

	@Test
	@DisplayName("feedback.batch.cron 케밥케이스 키를 주면 덮어써진다")
	void bindsCronFromKebabCaseKey() {
		contextRunner
			.withPropertyValues("feedback.batch.cron=0 50 8 * * MON-FRI")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(FeedbackBatchProperties.class).cron())
					.isEqualTo("0 50 8 * * MON-FRI");
			});
	}

	// 값이 문자열이라 오타가 나도 바인딩은 통과하고, 실제 실패는 기동 시점으로 미뤄진다.
	@Test
	@DisplayName("§C-1 크론 기본값이 실제로 파싱 가능한 cron 표현식이다")
	void specCronDefaultIsAParsableCronExpression() {
		contextRunner.run(context -> assertThatCode(
			() -> CronExpression.parse(context.getBean(FeedbackBatchProperties.class).cron()))
			.doesNotThrowAnyException());
	}

	// @Scheduled는 record가 아니라 Environment에서 읽으므로, 두 곳이 갈리면 운영 크론만 조용히 바뀐다.
	@Test
	@DisplayName("application.yml에 feedback.batch.cron이 §C-1 값으로 실제 존재한다")
	void applicationYmlDeclaresTheBatchCronKey() {
		new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(FeedbackBatchConfig.class)
			.run(context -> {
				Environment environment = context.getEnvironment();
				assertThat(environment.getProperty("feedback.batch.cron")).isEqualTo(SPEC_BATCH_CRON);
				assertThat(context.getBean(FeedbackBatchProperties.class).cron())
					.isEqualTo(SPEC_BATCH_CRON);
			});
	}
}
