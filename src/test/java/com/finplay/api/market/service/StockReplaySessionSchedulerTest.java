// StockReplaySessionScheduler의 08:40 KST 배치가 PREPARING→READY/FAILED 전환, 검증 완료 거래일 폴백 선택,
// 이미 확정된 세션 재확정 금지, StockCandle 불변을 올바르게 처리하는지 검증하는 단위 테스트
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.ImportStatus;
import com.finplay.api.market.domain.MarketDataImport;
import com.finplay.api.market.domain.PreparationStatus;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.MarketDataImportRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockReplaySessionSchedulerTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 2026-07-30(목) 08:40 KST — 직전 영업일은 주말·공휴일 없이 2026-07-29(수).
	private static final LocalDateTime WEEKDAY_RUN_AT = LocalDateTime.of(2026, 7, 30, 8, 40, 0);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 30);
	private static final LocalDate PREVIOUS_BUSINESS_DAY = LocalDate.of(2026, 7, 29);
	private static final LocalDate DAY_BEFORE_PREVIOUS_BUSINESS_DAY = LocalDate.of(2026, 7, 28);

	private final StockReplaySessionRepository stockReplaySessionRepository = mock(StockReplaySessionRepository.class);
	private final MarketDataImportRepository marketDataImportRepository = mock(MarketDataImportRepository.class);
	private final StockCandleRepository stockCandleRepository = mock(StockCandleRepository.class);

	private static Clock fixedClock(LocalDateTime dateTime) {
		return Clock.fixed(dateTime.atZone(KST).toInstant(), KST);
	}

	private StockReplaySessionScheduler newScheduler(Clock clock) {
		return new StockReplaySessionScheduler(
			stockReplaySessionRepository, marketDataImportRepository, stockCandleRepository, clock,
			new BusinessDayCalendar());
	}

	private static MarketDataImport successImport(LocalDate tradingDate) {
		return MarketDataImport.create("KIS", tradingDate, LocalDateTime.now(), ImportStatus.SUCCESS, null);
	}

	private static MarketDataImport partialSuccessImport(LocalDate tradingDate) {
		return MarketDataImport.create(
			"KIS", tradingDate, LocalDateTime.now(), ImportStatus.PARTIAL_SUCCESS, "000660 구조 오류");
	}

	private static MarketDataImport failedImport(LocalDate tradingDate) {
		return MarketDataImport.create("KIS", tradingDate, LocalDateTime.now(), ImportStatus.FAILED, "전체 응답 오류");
	}

	// 아직 오늘의 세션이 없을 때(최초 실행) preparing()으로 새로 만들어 orElseGet 경로를 태우고, save()에 넘긴
	// 인스턴스를 그대로 돌려주는 저장소를 흉내낸다 — 이후 resolveReady/resolveFailed가 같은 참조를 그대로 변경한다.
	private ArgumentCaptor<StockReplaySession> stubNoExistingSessionAndCaptureSaved() {
		when(stockReplaySessionRepository.findByServiceDate(SERVICE_DATE)).thenReturn(Optional.empty());
		ArgumentCaptor<StockReplaySession> captor = ArgumentCaptor.forClass(StockReplaySession.class);
		when(stockReplaySessionRepository.save(captor.capture()))
			.thenAnswer(invocation -> invocation.getArgument(0));
		return captor;
	}

	@Test
	void resolveTodaySessionTransitionsToReadyWithPreviousBusinessDayWhenItIsValidated() {
		ArgumentCaptor<StockReplaySession> savedSession = stubNoExistingSessionAndCaptureSaved();
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of(successImport(PREVIOUS_BUSINESS_DAY)));
		when(stockCandleRepository.existsByTradingDate(PREVIOUS_BUSINESS_DAY)).thenReturn(true);

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		StockReplaySession session = savedSession.getValue();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(PREVIOUS_BUSINESS_DAY);
		assertThat(session.getResolvedAt()).isEqualTo(WEEKDAY_RUN_AT);
		assertThat(session.getFailureReason()).isNull();
	}

	// PARTIAL_SUCCESS도 "검증 완료"로 인정한다(plan.md: SUCCESS·PARTIAL_SUCCESS 모두 받아들인 데이터).
	@Test
	void resolveTodaySessionTreatsPartialSuccessImportAsValidated() {
		ArgumentCaptor<StockReplaySession> savedSession = stubNoExistingSessionAndCaptureSaved();
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of(partialSuccessImport(PREVIOUS_BUSINESS_DAY)));
		when(stockCandleRepository.existsByTradingDate(PREVIOUS_BUSINESS_DAY)).thenReturn(true);

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		assertThat(savedSession.getValue().getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(savedSession.getValue().getSourceTradingDate()).isEqualTo(PREVIOUS_BUSINESS_DAY);
	}

	@Test
	void resolveTodaySessionFallsBackToDayBeforePreviousBusinessDayWhenPreviousIsNotYetValidated() {
		ArgumentCaptor<StockReplaySession> savedSession = stubNoExistingSessionAndCaptureSaved();
		// 직전 영업일(07-29)은 아직 수집 이력이 없음
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of());
		// 그 전 영업일(07-28)은 검증 완료됨
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(DAY_BEFORE_PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of(successImport(DAY_BEFORE_PREVIOUS_BUSINESS_DAY)));
		when(stockCandleRepository.existsByTradingDate(DAY_BEFORE_PREVIOUS_BUSINESS_DAY)).thenReturn(true);

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		StockReplaySession session = savedSession.getValue();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(DAY_BEFORE_PREVIOUS_BUSINESS_DAY);
	}

	// MarketDataImport는 SUCCESS로 남아있어도 실제 StockCandle 행이 없으면(예: 전량 롤백 등 극단 상황) 검증 완료로
	// 보지 않는다 — isValidatedTradingDate가 두 조건을 모두 요구하는지 확인한다.
	@Test
	void resolveTodaySessionDoesNotTreatDayAsValidatedWhenImportSucceededButNoStockCandleExists() {
		ArgumentCaptor<StockReplaySession> savedSession = stubNoExistingSessionAndCaptureSaved();
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of(successImport(PREVIOUS_BUSINESS_DAY)));
		when(stockCandleRepository.existsByTradingDate(PREVIOUS_BUSINESS_DAY)).thenReturn(false);
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(DAY_BEFORE_PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of(successImport(DAY_BEFORE_PREVIOUS_BUSINESS_DAY)));
		when(stockCandleRepository.existsByTradingDate(DAY_BEFORE_PREVIOUS_BUSINESS_DAY)).thenReturn(true);

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		assertThat(savedSession.getValue().getSourceTradingDate()).isEqualTo(DAY_BEFORE_PREVIOUS_BUSINESS_DAY);
	}

	@Test
	void resolveTodaySessionTransitionsToFailedWhenOnlyFailedImportsExistWithinLookback() {
		ArgumentCaptor<StockReplaySession> savedSession = stubNoExistingSessionAndCaptureSaved();
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(any(LocalDate.class)))
			.thenAnswer(invocation -> List.of(failedImport(invocation.getArgument(0))));

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		StockReplaySession session = savedSession.getValue();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(session.getSourceTradingDate()).isNull();
		assertThat(session.getResolvedAt()).isEqualTo(WEEKDAY_RUN_AT);
		assertThat(session.getFailureReason()).isEqualTo("검증 완료된 거래일 데이터를 찾지 못했습니다.");
	}

	@Test
	void resolveTodaySessionTransitionsToFailedWhenNoImportHistoryExistsAtAllWithinLookback() {
		ArgumentCaptor<StockReplaySession> savedSession = stubNoExistingSessionAndCaptureSaved();
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(any(LocalDate.class)))
			.thenReturn(List.of());

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		assertThat(savedSession.getValue().getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(savedSession.getValue().getSourceTradingDate()).isNull();
	}

	// StockReplaySessionScheduler는 StockCandle을 직접 저장·수정하지 않는다(spec.md MKT-005) — existsByTradingDate
	// 조회 외에 어떤 쓰기 메서드도 호출하지 않는지 확인한다.
	@Test
	void resolveTodaySessionNeverWritesToStockCandleRepository() {
		stubNoExistingSessionAndCaptureSaved();
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of(successImport(PREVIOUS_BUSINESS_DAY)));
		when(stockCandleRepository.existsByTradingDate(PREVIOUS_BUSINESS_DAY)).thenReturn(true);

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		verify(stockCandleRepository, never()).save(any());
		verify(stockCandleRepository, never()).saveAll(any());
		verify(stockCandleRepository, never()).delete(any());
		verify(stockCandleRepository, never()).deleteAll();
	}

	// 이미 READY로 확정된 세션은 같은 날 배치가 다시 실행돼도 원본 거래일을 바꾸지 않는다(멱등, spec.md MKT-002).
	@Test
	void resolveTodaySessionDoesNotReResolveWhenSessionIsAlreadyReady() {
		StockReplaySession alreadyReady = StockReplaySession.ready(
			SERVICE_DATE, PREVIOUS_BUSINESS_DAY, LocalDateTime.of(2026, 7, 30, 8, 40, 0), LocalDateTime.now());
		when(stockReplaySessionRepository.findByServiceDate(SERVICE_DATE)).thenReturn(Optional.of(alreadyReady));

		// 다음 날(7/31) 재실행을 흉내내기 위해 시각을 조금 늦춰도 되지만, 같은 clock으로도 충분히 재확정 금지를 검증한다.
		newScheduler(fixedClock(WEEKDAY_RUN_AT.plusMinutes(1))).resolveTodaySession();

		assertThat(alreadyReady.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(alreadyReady.getSourceTradingDate()).isEqualTo(PREVIOUS_BUSINESS_DAY);
		verify(stockReplaySessionRepository, never()).save(any());
		verifyNoInteractions(marketDataImportRepository);
		verifyNoInteractions(stockCandleRepository);
	}

	// 이미 FAILED로 확정된 세션도 마찬가지로 재확정하지 않는다.
	@Test
	void resolveTodaySessionDoesNotReResolveWhenSessionIsAlreadyFailed() {
		StockReplaySession alreadyFailed = StockReplaySession.failed(
			SERVICE_DATE, null, LocalDateTime.of(2026, 7, 30, 8, 40, 0), "검증 완료된 거래일 데이터를 찾지 못했습니다.",
			LocalDateTime.now());
		when(stockReplaySessionRepository.findByServiceDate(SERVICE_DATE)).thenReturn(Optional.of(alreadyFailed));

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		assertThat(alreadyFailed.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		verify(stockReplaySessionRepository, never()).save(any());
		verifyNoInteractions(marketDataImportRepository);
		verifyNoInteractions(stockCandleRepository);
	}

	// 08:40 실행 시나리오 — Clock 제어로 주말을 건너뛴 직전 영업일 계산까지 함께 검증한다.
	@Test
	void resolveTodaySessionSkipsWeekendWhenResolvingPreviousBusinessDayOnMondayRun() {
		// 2026-08-03(월) 08:40 KST 실행 — 주말(08-01 토, 08-02 일)을 건너뛰어 직전 영업일은 2026-07-31(금)이어야 한다.
		LocalDateTime mondayRunAt = LocalDateTime.of(2026, 8, 3, 8, 40, 0);
		LocalDate serviceDate = LocalDate.of(2026, 8, 3);
		LocalDate expectedFriday = LocalDate.of(2026, 7, 31);

		when(stockReplaySessionRepository.findByServiceDate(serviceDate)).thenReturn(Optional.empty());
		ArgumentCaptor<StockReplaySession> savedSession = ArgumentCaptor.forClass(StockReplaySession.class);
		when(stockReplaySessionRepository.save(savedSession.capture()))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(expectedFriday))
			.thenReturn(List.of(successImport(expectedFriday)));
		when(stockCandleRepository.existsByTradingDate(expectedFriday)).thenReturn(true);

		newScheduler(fixedClock(mondayRunAt)).resolveTodaySession();

		assertThat(savedSession.getValue().getSourceTradingDate()).isEqualTo(expectedFriday);
		assertThat(savedSession.getValue().getResolvedAt()).isEqualTo(mondayRunAt);
	}

	@Test
	void resolveTodaySessionSkipsWeekendAndHolidayTogetherWhenResolvingPreviousBusinessDay() {
		// 2026-08-18(화) 08:40 KST 실행 — 08-17(월, 공휴일)·08-16(일)·08-15(토, 공휴일)을 모두 건너뛰어
		// 직전 영업일은 2026-08-14(금)이어야 한다(holidays-2026.txt에 08-15·08-17 등재).
		LocalDateTime tuesdayRunAt = LocalDateTime.of(2026, 8, 18, 8, 40, 0);
		LocalDate serviceDate = LocalDate.of(2026, 8, 18);
		LocalDate expectedFriday = LocalDate.of(2026, 8, 14);

		when(stockReplaySessionRepository.findByServiceDate(serviceDate)).thenReturn(Optional.empty());
		ArgumentCaptor<StockReplaySession> savedSession = ArgumentCaptor.forClass(StockReplaySession.class);
		when(stockReplaySessionRepository.save(savedSession.capture()))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(expectedFriday))
			.thenReturn(List.of(successImport(expectedFriday)));
		when(stockCandleRepository.existsByTradingDate(expectedFriday)).thenReturn(true);

		newScheduler(fixedClock(tuesdayRunAt)).resolveTodaySession();

		assertThat(savedSession.getValue().getSourceTradingDate()).isEqualTo(expectedFriday);
	}
}
