package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.service.CryptoPriceSnapshotService;
import com.finplay.api.domain.market.service.StockReplaySessionScheduler;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class FeedbackBatchScheduleTest {

	private static final String SPEC_BATCH_CRON = "0 45 8 * * MON-FRI";

	private static final LocalDate WEEKDAY = LocalDate.of(2026, 8, 5);

	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);

	@Test
	@DisplayName("개장 전 배치에 zone = \"Asia/Seoul\"이 붙어 있다")
	void preMarketBatchDeclaresSeoulZone() throws NoSuchMethodException {
		assertThat(batchSchedule().zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("크론 값을 코드에 박지 않고 feedback.batch.cron 프로퍼티를 참조한다")
	void preMarketBatchReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(batchSchedule().cron()).isEqualTo("${feedback.batch.cron}");
	}

	@Test
	@DisplayName("재생세션 확정 배치와 다른 크론이고 그보다 뒤에 돈다")
	void preMarketBatchRunsOnItsOwnCronStrictlyAfterTheReplaySessionScheduler()
		throws NoSuchMethodException {
		String replaySessionCron = replaySessionSchedule().cron();
		assertThat(SPEC_BATCH_CRON).isNotEqualTo(replaySessionCron);

		LocalDateTime dayStart = WEEKDAY.atStartOfDay();
		LocalDateTime sessionRun = CronExpression.parse(replaySessionCron).next(dayStart);
		LocalDateTime batchRun = CronExpression.parse(SPEC_BATCH_CRON).next(dayStart);

		assertThat(batchRun).isNotNull();
		assertThat(sessionRun).isNotNull();
		assertThat(batchRun).as("배치가 세션 확정보다 먼저 돌면 매일 0건이 된다").isAfter(sessionRun);
	}

	@Test
	@DisplayName("개장 전 배치가 평일 개장 시각(09:00) 전에 돈다")
	void preMarketBatchRunsBeforeMarketOpenOnWeekdays() {
		LocalDateTime run = CronExpression.parse(SPEC_BATCH_CRON).next(WEEKDAY.atStartOfDay());

		assertThat(run.toLocalDate()).isEqualTo(WEEKDAY);
		assertThat(run.toLocalTime()).isBefore(MARKET_OPEN_TIME);
	}

	@Test
	@DisplayName("개장 전 배치는 주말에 돌지 않는다")
	void preMarketBatchDoesNotRunOnWeekends() {
		LocalDateTime saturday = LocalDateTime.of(2026, 8, 8, 0, 0);

		LocalDateTime next = CronExpression.parse(SPEC_BATCH_CRON).next(saturday);

		assertThat(next.getDayOfWeek().getValue()).isEqualTo(1);
	}

	@Test
	@DisplayName("코인 배치에 zone = \"Asia/Seoul\"이 붙어 있다")
	void cryptoBatchDeclaresSeoulZone() throws NoSuchMethodException {
		assertThat(cryptoBatchSchedule().zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("코인 배치가 크론 값을 코드에 박지 않고 feedback.batch.crypto-cron을 참조한다")
	void cryptoBatchReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(cryptoBatchSchedule().cron()).isEqualTo("${feedback.batch.crypto-cron}");
	}

	@Test
	@DisplayName("코인 배치가 주식 배치와 다른 크론 키를 참조한다")
	void cryptoBatchUsesItsOwnCronProperty() throws NoSuchMethodException {
		assertThat(cryptoBatchSchedule().cron()).isNotEqualTo(batchSchedule().cron());
	}

	@Test
	@DisplayName("코인 가격 스냅샷 배치에 zone = \"Asia/Seoul\"이 붙어 있다")
	void priceSnapshotScheduleDeclaresSeoulZone() throws NoSuchMethodException {
		assertThat(priceSnapshotSchedule().zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("코인 가격 스냅샷 배치가 크론 값을 코드에 박지 않고 market.crypto.price-snapshot-cron을 참조한다")
	void priceSnapshotScheduleReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(priceSnapshotSchedule().cron()).isEqualTo("${market.crypto.price-snapshot-cron}");
	}

	@Test
	@DisplayName("코인 변동 감시 배치에 zone = \"Asia/Seoul\"이 붙어 있다")
	void cryptoWatchScheduleDeclaresSeoulZone() throws NoSuchMethodException {
		assertThat(cryptoWatchSchedule().zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("코인 변동 감시 배치가 크론 값을 코드에 박지 않고 feedback.batch.crypto-watch-cron을 참조한다")
	void cryptoWatchScheduleReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(cryptoWatchSchedule().cron()).isEqualTo("${feedback.batch.crypto-watch-cron}");
	}

	@Test
	@DisplayName("코인 변동 감시 배치가 코인 가격 스냅샷 배치와 다른 크론 키를 참조한다")
	void cryptoWatchScheduleUsesItsOwnCronPropertyDistinctFromThePriceSnapshotSchedule() throws NoSuchMethodException {
		assertThat(cryptoWatchSchedule().cron()).isNotEqualTo(priceSnapshotSchedule().cron());
	}

	@Test
	@DisplayName("코인 집단 비교 배치에 zone = \"Asia/Seoul\"이 붙어 있다")
	void cryptoPeerStatsScheduleDeclaresSeoulZone() throws NoSuchMethodException {
		assertThat(cryptoPeerStatsSchedule().zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("코인 집단 비교 배치가 크론 값을 코드에 박지 않고 feedback.batch.crypto-peer-stats-cron을 참조한다")
	void cryptoPeerStatsScheduleReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(cryptoPeerStatsSchedule().cron()).isEqualTo("${feedback.batch.crypto-peer-stats-cron}");
	}

	@Test
	@DisplayName("코인 집단 비교 배치가 주식 집단 비교 배치와 다른 크론 키를 참조한다")
	void cryptoPeerStatsScheduleUsesItsOwnCronPropertyDistinctFromTheStockPeerStatsSchedule()
		throws NoSuchMethodException {
		assertThat(cryptoPeerStatsSchedule().cron()).isNotEqualTo(peerStatsSchedule().cron());
	}

	private static Scheduled cryptoPeerStatsSchedule() throws NoSuchMethodException {
		return schedule(PeerStatsBatchService.class, "runCryptoPeerStatsBatch");
	}

	private static Scheduled peerStatsSchedule() throws NoSuchMethodException {
		return schedule(PeerStatsBatchService.class, "runPeerStatsBatch");
	}

	private static Scheduled cryptoWatchSchedule() throws NoSuchMethodException {
		return schedule(CryptoPriceMoveWatcher.class, "watch");
	}

	private static Scheduled priceSnapshotSchedule() throws NoSuchMethodException {
		return schedule(CryptoPriceSnapshotService.class, "recordSnapshots");
	}

	private static Scheduled cryptoBatchSchedule() throws NoSuchMethodException {
		return schedule(CryptoFeedbackBatchService.class, "refreshCryptoFeedback");
	}

	private static Scheduled batchSchedule() throws NoSuchMethodException {
		return schedule(FeedbackBatchService.class, "runPreMarketBatch");
	}

	private static Scheduled replaySessionSchedule() throws NoSuchMethodException {
		return schedule(StockReplaySessionScheduler.class, "resolveTodaySession");
	}

	private static Scheduled schedule(Class<?> type, String methodName) throws NoSuchMethodException {
		Scheduled annotation = type.getMethod(methodName).getAnnotation(Scheduled.class);
		assertThat(annotation).as("%s.%s에 @Scheduled가 없다", type.getSimpleName(), methodName).isNotNull();
		return annotation;
	}
}
