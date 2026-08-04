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

	// §C-1 코인 요약·브리핑 갱신 크론 (매시 05분)
	private static final String SPEC_CRYPTO_CRON = "0 5 * * * *";

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
	//
	// spring.config.additional-location을 비워 두고 돌린다. build.gradle이 test 태스크 전체에 이 시스템
	// 프로퍼티를 걸어 feedback-schedules-disabled-for-tests.yml을 얹는데(테스트 중 배치 스케줄이 실제로
	// 등록되는 것을 막는다), ConfigDataApplicationContextInitializer는 그 프로퍼티를 @SpringBootTest와 똑같이
	// 해석하므로 여기서도 크론이 "-"로 덮여 보인다(실측). 이 테스트가 보려는 것은 application.yml에 적힌 값
	// 자체다 — withSystemProperties는 run() 동안만 적용하고 끝나면 원래 값을 되돌린다.
	@Test
	@DisplayName("application.yml에 feedback.batch.cron이 §C-1 값으로 실제 존재한다")
	void applicationYmlDeclaresTheBatchCronKey() {
		new ApplicationContextRunner()
			.withSystemProperties("spring.config.additional-location=")
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(FeedbackBatchConfig.class)
			.run(context -> {
				Environment environment = context.getEnvironment();
				assertThat(environment.getProperty("feedback.batch.cron")).isEqualTo(SPEC_BATCH_CRON);
				assertThat(context.getBean(FeedbackBatchProperties.class).cron())
					.isEqualTo(SPEC_BATCH_CRON);
			});
	}

	// --- 코인 배치 크론 (이슈 #188 항목 7) ---

	@Test
	@DisplayName("feedback.batch 설정을 주지 않아도 §C-1 코인 크론으로 바인딩된다")
	void bindsSpecCryptoCronDefaultWhenNoFeedbackBatchPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(FeedbackBatchProperties.class).cryptoCron())
				.isEqualTo(SPEC_CRYPTO_CRON);
		});
	}

	@Test
	@DisplayName("feedback.batch.crypto-cron 케밥케이스 키를 주면 덮어써지고 주식 크론은 그대로다")
	void bindsCryptoCronFromKebabCaseKey() {
		contextRunner
			.withPropertyValues("feedback.batch.crypto-cron=0 15 * * * *")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackBatchProperties properties = context.getBean(FeedbackBatchProperties.class);
				assertThat(properties.cryptoCron()).isEqualTo("0 15 * * * *");
				assertThat(properties.cron()).isEqualTo(SPEC_BATCH_CRON);
			});
	}

	// 매시 크론이라 오타가 나면 어긋남이 눈에 덜 띈다 — 파싱 가능성을 먼저 확정한다.
	@Test
	@DisplayName("§C-1 코인 크론 기본값이 실제로 파싱 가능하고 매시 05분에 돈다")
	void specCryptoCronRunsAtFiveMinutesPastEveryHour() {
		contextRunner.run(context -> {
			String cryptoCron = context.getBean(FeedbackBatchProperties.class).cryptoCron();
			assertThatCode(() -> CronExpression.parse(cryptoCron)).doesNotThrowAnyException();

			java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 5, 10, 0);
			java.time.LocalDateTime next = CronExpression.parse(cryptoCron).next(from);
			assertThat(next).isEqualTo(java.time.LocalDateTime.of(2026, 8, 5, 10, 5));
			// 매시라 다음 실행은 정확히 한 시간 뒤다 — 하루 1회로 좁아지는 회귀가 여기서 걸린다.
			assertThat(CronExpression.parse(cryptoCron).next(next))
				.isEqualTo(java.time.LocalDateTime.of(2026, 8, 5, 11, 5));
		});
	}

	@Test
	@DisplayName("application.yml에 feedback.batch.crypto-cron이 §C-1 값으로 실제 존재한다")
	void applicationYmlDeclaresTheCryptoCronKey() {
		new ApplicationContextRunner()
			.withSystemProperties("spring.config.additional-location=")
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(FeedbackBatchConfig.class)
			.run(context -> {
				Environment environment = context.getEnvironment();
				assertThat(environment.getProperty("feedback.batch.crypto-cron")).isEqualTo(SPEC_CRYPTO_CRON);
				assertThat(context.getBean(FeedbackBatchProperties.class).cryptoCron())
					.isEqualTo(SPEC_CRYPTO_CRON);
			});
	}
}
