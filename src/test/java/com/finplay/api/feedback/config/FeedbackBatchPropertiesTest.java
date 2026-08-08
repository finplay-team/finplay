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

	// §C-1 주식 장 마감 집단 비교 확정 집계 크론 (15:32)
	private static final String SPEC_PEER_STATS_CRON = "0 32 15 * * MON-FRI";

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

	// --- 코인 변동 감시 크론 (CryptoPriceMoveWatcher, 이슈 #225 항목 3) ---

	// §C-1 코인 변동 감시 크론 — market.crypto.price-snapshot-cron(매 분 정각)과 초를 30초 어긋낸다.
	private static final String SPEC_CRYPTO_WATCH_CRON = "30 * * * * *";

	@Test
	@DisplayName("feedback.batch 설정을 주지 않아도 §C-1 코인 변동 감시 크론으로 바인딩된다")
	void bindsSpecCryptoWatchCronDefaultWhenNoFeedbackBatchPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(FeedbackBatchProperties.class).cryptoWatchCron())
				.isEqualTo(SPEC_CRYPTO_WATCH_CRON);
		});
	}

	@Test
	@DisplayName("feedback.batch.crypto-watch-cron 케밥케이스 키를 주면 덮어써지고 나머지 크론은 그대로다")
	void bindsCryptoWatchCronFromKebabCaseKey() {
		contextRunner
			.withPropertyValues("feedback.batch.crypto-watch-cron=15 * * * * *")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackBatchProperties properties = context.getBean(FeedbackBatchProperties.class);
				assertThat(properties.cryptoWatchCron()).isEqualTo("15 * * * * *");
				assertThat(properties.cron()).isEqualTo(SPEC_BATCH_CRON);
				assertThat(properties.cryptoCron()).isEqualTo(SPEC_CRYPTO_CRON);
			});
	}

	@Test
	@DisplayName("§C-1 코인 변동 감시 크론 기본값이 실제로 파싱 가능하고 매 분 30초에 돈다")
	void specCryptoWatchCronRunsAtThirtySecondsPastEveryMinute() {
		contextRunner.run(context -> {
			String cryptoWatchCron = context.getBean(FeedbackBatchProperties.class).cryptoWatchCron();
			assertThatCode(() -> CronExpression.parse(cryptoWatchCron)).doesNotThrowAnyException();

			java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 5, 10, 0, 0);
			java.time.LocalDateTime next = CronExpression.parse(cryptoWatchCron).next(from);
			assertThat(next).isEqualTo(java.time.LocalDateTime.of(2026, 8, 5, 10, 0, 30));
			assertThat(CronExpression.parse(cryptoWatchCron).next(next))
				.isEqualTo(java.time.LocalDateTime.of(2026, 8, 5, 10, 1, 30));
		});
	}

	@Test
	@DisplayName("코인 변동 감시 크론이 코인 가격 스냅샷 크론(매 분 정각)과 초가 30초 어긋난다")
	void cryptoWatchCronIsOffsetByThirtySecondsFromThePriceSnapshotCron() {
		contextRunner.run(context -> {
			String cryptoWatchCron = context.getBean(FeedbackBatchProperties.class).cryptoWatchCron();
			// market.crypto.price-snapshot-cron의 값 — 이 클래스가 소유하지 않으므로 §C-1 그대로 리터럴로 대조한다.
			String priceSnapshotCron = "0 * * * * *";

			// :00 정각이 아니라 :45에서 출발해야 두 next()가 같은 분(10:00)의 실행을 가리킨다 — 정각에서 출발하면
			// 스냅샷 크론의 다음 실행이 "그다음 분"으로 넘어가 버려 두 실행 시각이 1분 가까이 벌어진다.
			java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 5, 9, 59, 45);
			java.time.LocalDateTime watchRun = CronExpression.parse(cryptoWatchCron).next(from);
			java.time.LocalDateTime snapshotRun = CronExpression.parse(priceSnapshotCron).next(from);

			assertThat(snapshotRun).isEqualTo(java.time.LocalDateTime.of(2026, 8, 5, 10, 0, 0));
			assertThat(watchRun).isEqualTo(java.time.LocalDateTime.of(2026, 8, 5, 10, 0, 30));
			assertThat(watchRun).isNotEqualTo(snapshotRun);
			assertThat(java.time.Duration.between(snapshotRun, watchRun).getSeconds()).isEqualTo(30);
		});
	}

	@Test
	@DisplayName("application.yml에 feedback.batch.crypto-watch-cron이 §C-1 값으로 실제 존재한다")
	void applicationYmlDeclaresTheCryptoWatchCronKey() {
		new ApplicationContextRunner()
			.withSystemProperties("spring.config.additional-location=")
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(FeedbackBatchConfig.class)
			.run(context -> {
				Environment environment = context.getEnvironment();
				assertThat(environment.getProperty("feedback.batch.crypto-watch-cron"))
					.isEqualTo(SPEC_CRYPTO_WATCH_CRON);
				assertThat(context.getBean(FeedbackBatchProperties.class).cryptoWatchCron())
					.isEqualTo(SPEC_CRYPTO_WATCH_CRON);
			});
	}

	// --- 코인 집단 비교 확정 집계 크론 (§FEED-012 결정 3, 이슈 #275 항목 1) ---

	// §C-1 코인 집단 비교 확정 집계 크론 — 매일 00:05에 전날 KST 하루치 코인 카드를 집계한다.
	private static final String SPEC_CRYPTO_PEER_STATS_CRON = "0 5 0 * * *";

	@Test
	@DisplayName("feedback.batch 설정을 주지 않아도 §C-1 코인 집단 비교 크론으로 바인딩된다")
	void bindsSpecCryptoPeerStatsCronDefaultWhenNoFeedbackBatchPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(FeedbackBatchProperties.class).cryptoPeerStatsCron())
				.isEqualTo(SPEC_CRYPTO_PEER_STATS_CRON);
		});
	}

	@Test
	@DisplayName("feedback.batch.crypto-peer-stats-cron 케밥케이스 키를 주면 덮어써지고 나머지 크론은 그대로다")
	void bindsCryptoPeerStatsCronFromKebabCaseKey() {
		contextRunner
			.withPropertyValues("feedback.batch.crypto-peer-stats-cron=0 10 0 * * *")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackBatchProperties properties = context.getBean(FeedbackBatchProperties.class);
				assertThat(properties.cryptoPeerStatsCron()).isEqualTo("0 10 0 * * *");
				assertThat(properties.cron()).isEqualTo(SPEC_BATCH_CRON);
				assertThat(properties.cryptoCron()).isEqualTo(SPEC_CRYPTO_CRON);
				assertThat(properties.cryptoWatchCron()).isEqualTo(SPEC_CRYPTO_WATCH_CRON);
				// 주식 장 마감 집계 크론과 별개 필드다 — 한쪽을 바꿔 다른 쪽이 따라 움직이면 안 된다.
				assertThat(properties.peerStatsCron()).isEqualTo(SPEC_PEER_STATS_CRON);
			});
	}

	// 매시 크론(`0 5 * * * *`)과 한 글자 차이라 오타가 나면 하루 1회가 매시 24회로 조용히 늘어난다.
	@Test
	@DisplayName("§C-1 코인 집단 비교 크론 기본값이 파싱 가능하고 매일 00:05 하루 1회만 돈다")
	void specCryptoPeerStatsCronRunsOnceADayAtFiveMinutesPastMidnight() {
		contextRunner.run(context -> {
			String cron = context.getBean(FeedbackBatchProperties.class).cryptoPeerStatsCron();
			assertThatCode(() -> CronExpression.parse(cron)).doesNotThrowAnyException();

			java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 5, 10, 0);
			java.time.LocalDateTime next = CronExpression.parse(cron).next(from);
			assertThat(next).isEqualTo(java.time.LocalDateTime.of(2026, 8, 6, 0, 5));
			// 다음 실행이 정확히 하루 뒤여야 한다 — 매시로 넓어지는 회귀가 여기서 걸린다.
			assertThat(CronExpression.parse(cron).next(next))
				.isEqualTo(java.time.LocalDateTime.of(2026, 8, 7, 0, 5));
		});
	}

	// 주식 집계는 MON-FRI지만 코인은 장 마감이 없어 주말에도 돌아야 한다(§FEED-012 결정 3).
	@Test
	@DisplayName("코인 집단 비교 크론은 주말에도 실행된다")
	void specCryptoPeerStatsCronAlsoRunsOnWeekends() {
		contextRunner.run(context -> {
			CronExpression cron = CronExpression
				.parse(context.getBean(FeedbackBatchProperties.class).cryptoPeerStatsCron());

			// 2026-08-08은 토요일 — 다음 실행이 일요일 00:05이어야 주말이 건너뛰이지 않는다.
			java.time.LocalDateTime saturday = java.time.LocalDateTime.of(2026, 8, 8, 1, 0);
			assertThat(cron.next(saturday)).isEqualTo(java.time.LocalDateTime.of(2026, 8, 9, 0, 5));
		});
	}

	@Test
	@DisplayName("application.yml에 feedback.batch.crypto-peer-stats-cron이 §C-1 값으로 실제 존재한다")
	void applicationYmlDeclaresTheCryptoPeerStatsCronKey() {
		new ApplicationContextRunner()
			.withSystemProperties("spring.config.additional-location=")
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(FeedbackBatchConfig.class)
			.run(context -> {
				Environment environment = context.getEnvironment();
				assertThat(environment.getProperty("feedback.batch.crypto-peer-stats-cron"))
					.isEqualTo(SPEC_CRYPTO_PEER_STATS_CRON);
				assertThat(context.getBean(FeedbackBatchProperties.class).cryptoPeerStatsCron())
					.isEqualTo(SPEC_CRYPTO_PEER_STATS_CRON);
			});
	}

	// yml과 @DefaultValue가 갈리는지는 키마다 따로 보고 있지만, 새 키가 추가될 때 이 대조를 빠뜨리기 쉽다.
	// 컨테이너 없이 도는 테스트이므로 5개 전부를 한 자리에서 한 번 더 묶어 둔다.
	@Test
	@DisplayName("feedback.batch의 크론 5개가 yml과 @DefaultValue 양쪽에서 모두 §C-1 값이다")
	void allBatchCronsAgreeBetweenYmlAndDefaults() {
		new ApplicationContextRunner()
			.withSystemProperties("spring.config.additional-location=")
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(FeedbackBatchConfig.class)
			.run(context -> {
				Environment environment = context.getEnvironment();
				FeedbackBatchProperties properties = context.getBean(FeedbackBatchProperties.class);

				assertThat(properties.cron()).isEqualTo(SPEC_BATCH_CRON);
				assertThat(properties.cryptoCron()).isEqualTo(SPEC_CRYPTO_CRON);
				assertThat(properties.peerStatsCron()).isEqualTo(SPEC_PEER_STATS_CRON);
				assertThat(properties.cryptoWatchCron()).isEqualTo(SPEC_CRYPTO_WATCH_CRON);
				assertThat(properties.cryptoPeerStatsCron()).isEqualTo(SPEC_CRYPTO_PEER_STATS_CRON);

				assertThat(environment.getProperty("feedback.batch.cron")).isEqualTo(SPEC_BATCH_CRON);
				assertThat(environment.getProperty("feedback.batch.crypto-cron")).isEqualTo(SPEC_CRYPTO_CRON);
				assertThat(environment.getProperty("feedback.batch.peer-stats-cron"))
					.isEqualTo(SPEC_PEER_STATS_CRON);
				assertThat(environment.getProperty("feedback.batch.crypto-watch-cron"))
					.isEqualTo(SPEC_CRYPTO_WATCH_CRON);
				assertThat(environment.getProperty("feedback.batch.crypto-peer-stats-cron"))
					.isEqualTo(SPEC_CRYPTO_PEER_STATS_CRON);
			});
	}
}
