package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;

class FeedbackBatchPropertiesTest {

	private static final String SPEC_BATCH_CRON = "0 45 8 * * MON-FRI";

	private static final String SPEC_CRYPTO_CRON = "0 5 * * * *";

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

	@Test
	@DisplayName("§C-1 크론 기본값이 실제로 파싱 가능한 cron 표현식이다")
	void specCronDefaultIsAParsableCronExpression() {
		contextRunner.run(context -> assertThatCode(
			() -> CronExpression.parse(context.getBean(FeedbackBatchProperties.class).cron()))
			.doesNotThrowAnyException());
	}

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

	@Test
	@DisplayName("§C-1 코인 크론 기본값이 실제로 파싱 가능하고 매시 05분에 돈다")
	void specCryptoCronRunsAtFiveMinutesPastEveryHour() {
		contextRunner.run(context -> {
			String cryptoCron = context.getBean(FeedbackBatchProperties.class).cryptoCron();
			assertThatCode(() -> CronExpression.parse(cryptoCron)).doesNotThrowAnyException();

			java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 5, 10, 0);
			java.time.LocalDateTime next = CronExpression.parse(cryptoCron).next(from);
			assertThat(next).isEqualTo(java.time.LocalDateTime.of(2026, 8, 5, 10, 5));
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
			String priceSnapshotCron = "0 * * * * *";

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
				assertThat(properties.peerStatsCron()).isEqualTo(SPEC_PEER_STATS_CRON);
			});
	}

	@Test
	@DisplayName("§C-1 코인 집단 비교 크론 기본값이 파싱 가능하고 매일 00:05 하루 1회만 돈다")
	void specCryptoPeerStatsCronRunsOnceADayAtFiveMinutesPastMidnight() {
		contextRunner.run(context -> {
			String cron = context.getBean(FeedbackBatchProperties.class).cryptoPeerStatsCron();
			assertThatCode(() -> CronExpression.parse(cron)).doesNotThrowAnyException();

			java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 5, 10, 0);
			java.time.LocalDateTime next = CronExpression.parse(cron).next(from);
			assertThat(next).isEqualTo(java.time.LocalDateTime.of(2026, 8, 6, 0, 5));
			assertThat(CronExpression.parse(cron).next(next))
				.isEqualTo(java.time.LocalDateTime.of(2026, 8, 7, 0, 5));
		});
	}

	@Test
	@DisplayName("코인 집단 비교 크론은 주말에도 실행된다")
	void specCryptoPeerStatsCronAlsoRunsOnWeekends() {
		contextRunner.run(context -> {
			CronExpression cron = CronExpression
				.parse(context.getBean(FeedbackBatchProperties.class).cryptoPeerStatsCron());

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
