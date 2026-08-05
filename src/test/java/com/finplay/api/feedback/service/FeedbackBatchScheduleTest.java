// 개장 전 배치가 재생세션 확정 배치와 분리된 크론에서 zone과 함께 도는지 CronExpression으로 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.service.CryptoPriceSnapshotService;
import com.finplay.api.market.service.StockReplaySessionScheduler;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

// 완료 조건 배치 ②의 앞쪽 절반(분리된 크론)이다. 뒤쪽 절반(READY가 아니면 아무것도 안 한다)은
// FeedbackBatchServiceTest가 맡는다.
//
// 저장 테스트로는 이 축이 드러나지 않는다 — 배치를 직접 부르는 테스트는 크론이 무엇이든 통과하고,
// 운영에서만 엉뚱한 시각에 돌거나 세션보다 먼저 돈다. 그래서 표현식 자체를 단정한다
// (NewsCollectionScheduleTest와 같은 형태).
//
// 기대값의 정본은 spec.md §C-1이다.
class FeedbackBatchScheduleTest {

	// §C-1 개장 전 배치 크론
	private static final String SPEC_BATCH_CRON = "0 45 8 * * MON-FRI";

	private static final LocalDate WEEKDAY = LocalDate.of(2026, 8, 5);

	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);

	// §C-1 — cron 기반 @Scheduled에 zone을 빠뜨리면 배포 JVM 기본 타임존이 UTC라 08:45 배치가 KST 17:45에
	// 돌아 장중 내내 화면이 비고 예외도 로그도 남지 않는다. 붙어 있는지는 애노테이션을 읽어야만 알 수 있다.
	@Test
	@DisplayName("개장 전 배치에 zone = \"Asia/Seoul\"이 붙어 있다")
	void preMarketBatchDeclaresSeoulZone() throws NoSuchMethodException {
		assertThat(batchSchedule().zone()).isEqualTo("Asia/Seoul");
	}

	// 크론 값을 코드에 박지 않는다 — 정본은 §C-1이고 운영 조정은 application.yml에서 한다(§C-7).
	@Test
	@DisplayName("크론 값을 코드에 박지 않고 feedback.batch.cron 프로퍼티를 참조한다")
	void preMarketBatchReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(batchSchedule().cron()).isEqualTo("${feedback.batch.cron}");
	}

	// FEED-004 — 재생세션 확정 배치에 이어 붙이지 않는다. 같은 시각 크론 둘은 실행 순서가 보장되지 않아
	// 세션이 아직 PREPARING인 채로 배치가 돌면 매일 조용히 0건이 된다.
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

	// 개장 전에 끝나야 하는 산출물이라(§C-6 생성 순서) 실행 자체가 09:00 전이어야 한다.
	@Test
	@DisplayName("개장 전 배치가 평일 개장 시각(09:00) 전에 돈다")
	void preMarketBatchRunsBeforeMarketOpenOnWeekdays() {
		LocalDateTime run = CronExpression.parse(SPEC_BATCH_CRON).next(WEEKDAY.atStartOfDay());

		assertThat(run.toLocalDate()).isEqualTo(WEEKDAY);
		assertThat(run.toLocalTime()).isBefore(MARKET_OPEN_TIME);
	}

	// 주식 배치라 주말에는 돌 이유가 없다 — 토요일에서 출발하면 다음 실행이 월요일이어야 한다.
	@Test
	@DisplayName("개장 전 배치는 주말에 돌지 않는다")
	void preMarketBatchDoesNotRunOnWeekends() {
		LocalDateTime saturday = LocalDateTime.of(2026, 8, 8, 0, 0);

		LocalDateTime next = CronExpression.parse(SPEC_BATCH_CRON).next(saturday);

		assertThat(next.getDayOfWeek().getValue()).isEqualTo(1);
	}

	// --- 코인 배치 (이슈 #188 항목 7) ---
	//
	// zone을 빠뜨리면 매시 크론이라 어긋남이 눈에 덜 띄는 만큼 더 위험하다 — origin_trade_date가 KST 날짜라
	// 자정 부근에서 하루가 밀린 행이 생긴다. 붙어 있는지는 애노테이션을 읽어야만 알 수 있다.
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

	// 주식 배치와 같은 키를 참조하면 코인이 평일 08:45에만 돌고 매시 갱신이 사라진다 — 그래도 예외는 없다.
	@Test
	@DisplayName("코인 배치가 주식 배치와 다른 크론 키를 참조한다")
	void cryptoBatchUsesItsOwnCronProperty() throws NoSuchMethodException {
		assertThat(cryptoBatchSchedule().cron()).isNotEqualTo(batchSchedule().cron());
	}

	// --- 코인 가격 스냅샷 배치 (market 소유, spec 012 §코인 가격 스냅샷, 이슈 #225 항목 1) ---
	//
	// market 소유 스케줄이라 이 클래스(feedback)가 직접 다룰 배치는 아니지만, tasks.md 항목 1의 검증 지시가
	// "FeedbackBatchScheduleTest 계열에" 추가하라고 명시했다 — 이 파일이 이미 zone·프로퍼티 참조를
	// CronExpression으로 단정하는 유일한 장소이기 때문이다.

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

	// --- 코인 변동 감시 배치 (CryptoPriceMoveWatcher, spec 012 §탐지 알고리즘(코인), 이슈 #225 항목 3) ---
	//
	// zone을 빠뜨리면 매 분 도는 크론이라 어긋남이 눈에 덜 띈다 — origin_trade_date가 KST 날짜라 자정 부근에서
	// 하루가 밀린 행이 생긴다. 붙어 있는지는 애노테이션을 읽어야만 알 수 있다.
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

	// 같은 시각이면 실행 순서가 보장되지 않아 감시가 그 분의 스냅샷을 못 볼 수 있다(§C-1) — 두 배치가 서로
	// 다른 크론 키를 참조하는지를 못박는다.
	@Test
	@DisplayName("코인 변동 감시 배치가 코인 가격 스냅샷 배치와 다른 크론 키를 참조한다")
	void cryptoWatchScheduleUsesItsOwnCronPropertyDistinctFromThePriceSnapshotSchedule() throws NoSuchMethodException {
		assertThat(cryptoWatchSchedule().cron()).isNotEqualTo(priceSnapshotSchedule().cron());
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
