package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
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
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of());
		when(marketDataImportRepository.findBySourceTradingDateOrderByCollectedAtDesc(DAY_BEFORE_PREVIOUS_BUSINESS_DAY))
			.thenReturn(List.of(successImport(DAY_BEFORE_PREVIOUS_BUSINESS_DAY)));
		when(stockCandleRepository.existsByTradingDate(DAY_BEFORE_PREVIOUS_BUSINESS_DAY)).thenReturn(true);

		newScheduler(fixedClock(WEEKDAY_RUN_AT)).resolveTodaySession();

		StockReplaySession session = savedSession.getValue();
		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(DAY_BEFORE_PREVIOUS_BUSINESS_DAY);
	}

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

	@Test
	void resolveTodaySessionDoesNotReResolveWhenSessionIsAlreadyReady() {
		StockReplaySession alreadyReady = StockReplaySession.ready(
			SERVICE_DATE, PREVIOUS_BUSINESS_DAY, LocalDateTime.of(2026, 7, 30, 8, 40, 0), LocalDateTime.now());
		when(stockReplaySessionRepository.findByServiceDate(SERVICE_DATE)).thenReturn(Optional.of(alreadyReady));

		newScheduler(fixedClock(WEEKDAY_RUN_AT.plusMinutes(1))).resolveTodaySession();

		assertThat(alreadyReady.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(alreadyReady.getSourceTradingDate()).isEqualTo(PREVIOUS_BUSINESS_DAY);
		verify(stockReplaySessionRepository, never()).save(any());
		verifyNoInteractions(marketDataImportRepository);
		verifyNoInteractions(stockCandleRepository);
	}

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

	@Test
	void resolveTodaySessionSkipsWeekendWhenResolvingPreviousBusinessDayOnMondayRun() {
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
