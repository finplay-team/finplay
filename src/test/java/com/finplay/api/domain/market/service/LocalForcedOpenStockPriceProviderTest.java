package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocalForcedOpenStockPriceProviderTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime AFTER_HOURS = LocalDateTime.of(SERVICE_DATE, LocalTime.of(22, 0));
	private static final Long INSTRUMENT_ID = 1L;

	private final KisHistoricalReplayPriceProvider delegate = mock(KisHistoricalReplayPriceProvider.class);
	private final StockReplaySessionRepository stockReplaySessionRepository = mock(StockReplaySessionRepository.class);
	private final Clock clock = Clock.fixed(AFTER_HOURS.atZone(KST).toInstant(), KST);

	@Test
	void getMarketStatusReturnsDelegateValueWhenForceFlagIsOff() {
		when(delegate.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);

		assertThat(provider(false).getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		verifyNoInteractions(stockReplaySessionRepository);
	}

	@Test
	void getMarketStatusForcesOpenWhenFlagIsOnAndTodaySessionIsReady() {
		when(delegate.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		when(stockReplaySessionRepository.findByServiceDate(SERVICE_DATE))
			.thenReturn(Optional.of(readySession()));

		assertThat(provider(true).getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);
	}

	@Test
	void getMarketStatusStaysClosedWhenFlagIsOnButTodaySessionIsMissing() {
		when(delegate.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		when(stockReplaySessionRepository.findByServiceDate(SERVICE_DATE)).thenReturn(Optional.empty());

		assertThat(provider(true).getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getMarketStatusStaysClosedWhenFlagIsOnButTodaySessionFailed() {
		when(delegate.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		when(stockReplaySessionRepository.findByServiceDate(SERVICE_DATE))
			.thenReturn(Optional.of(failedSession()));

		assertThat(provider(true).getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(failedSession().getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
	}

	@Test
	void getMarketStatusSkipsSessionLookupWhenDelegateAlreadyReportsOpen() {
		when(delegate.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);

		assertThat(provider(true).getMarketStatus()).isEqualTo(StockMarketStatus.OPEN);
		verifyNoInteractions(stockReplaySessionRepository);
	}

	@Test
	void priceAndCandleQueriesAreDelegatedUnchanged() {
		StockReplayPriceDto price = new StockReplayPriceDto(false, StockMarketStatus.CLOSED, null, null, null);
		when(delegate.getCurrentPrice(INSTRUMENT_ID)).thenReturn(price);
		when(delegate.getCurrentPrices(List.of(INSTRUMENT_ID))).thenReturn(List.of(price));
		when(delegate.getCandles(INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null)).thenReturn(List.of());
		LocalForcedOpenStockPriceProvider provider = provider(true);

		assertThat(provider.getCurrentPrice(INSTRUMENT_ID)).isSameAs(price);
		assertThat(provider.getCurrentPrices(List.of(INSTRUMENT_ID))).containsExactly(price);
		assertThat(provider.getCandles(INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null)).isEmpty();
		verify(delegate).getCurrentPrice(INSTRUMENT_ID);
		verify(delegate).getCurrentPrices(any());
		verify(delegate).getCandles(INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null);
	}

	@Test
	void getCurrentPriceForcesOpenAndPreservesPriceAndSessionWhenReadyQuoteIsClosed() {
		StockReplaySession session = readySession();
		StockReplayPriceDto quote = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, session.getSourceTradingDate(), new BigDecimal("71000"),
			AFTER_HOURS, session);
		when(delegate.getCurrentPrice(INSTRUMENT_ID)).thenReturn(quote);

		StockReplayPriceDto result = provider(true).getCurrentPrice(INSTRUMENT_ID);

		assertThat(result.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(result.price()).isEqualByComparingTo("71000");
		assertThat(result.replaySession()).isSameAs(session);
	}

	@Test
	void getCurrentPriceDoesNotForceFallbackQuoteOpenBecauseSessionIsNotReady() {
		LocalDate fallbackTradingDate = SERVICE_DATE.minusDays(5);
		StockReplayPriceDto fallbackQuote = new StockReplayPriceDto(
			false, StockMarketStatus.CLOSED, fallbackTradingDate, new BigDecimal("71000"),
			AFTER_HOURS.minusDays(5), null);
		when(delegate.getCurrentPrice(INSTRUMENT_ID)).thenReturn(fallbackQuote);

		StockReplayPriceDto result = provider(true).getCurrentPrice(INSTRUMENT_ID);

		assertThat(result).isSameAs(fallbackQuote);
		assertThat(result.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(result.sessionReady()).isFalse();
		assertThat(result.replaySession()).isNull();
	}

	@Test
	void getCurrentPricesDoesNotForceFallbackQuotesOpenWhileStillForcingReadyClosedQuotes() {
		StockReplaySession readySessionInList = readySession();
		LocalDate fallbackTradingDate = SERVICE_DATE.minusDays(5);
		StockReplayPriceDto readyClosed = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, readySessionInList.getSourceTradingDate(), new BigDecimal("71000"),
			AFTER_HOURS.minusHours(7), readySessionInList);
		StockReplayPriceDto fallbackQuote = new StockReplayPriceDto(
			false, StockMarketStatus.CLOSED, fallbackTradingDate, new BigDecimal("65000"),
			AFTER_HOURS.minusDays(5), null);
		List<Long> instrumentIds = List.of(1L, 2L);
		when(delegate.getCurrentPrices(instrumentIds)).thenReturn(List.of(readyClosed, fallbackQuote));

		List<StockReplayPriceDto> results = provider(true).getCurrentPrices(instrumentIds);

		assertThat(results).hasSize(2);
		assertThat(results.get(0).marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(results.get(1)).isSameAs(fallbackQuote);
		assertThat(results.get(1).marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(results.get(1).sessionReady()).isFalse();
		assertThat(results.get(1).replaySession()).isNull();
	}

	@Test
	void getCurrentPriceKeepsClosedQuoteAndSameSessionWhenForceFlagIsOff() {
		StockReplaySession session = readySession();
		StockReplayPriceDto quote = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, session.getSourceTradingDate(), new BigDecimal("71000"),
			AFTER_HOURS, session);
		when(delegate.getCurrentPrice(INSTRUMENT_ID)).thenReturn(quote);

		StockReplayPriceDto result = provider(false).getCurrentPrice(INSTRUMENT_ID);

		assertThat(result).isSameAs(quote);
		assertThat(result.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(result.replaySession()).isSameAs(session);
	}

	@Test
	void getCurrentPricesForcesOnlyReadyClosedQuotesOpenAndPreservesOrderAndPayload() {
		StockReplaySession firstSession = readySession();
		StockReplaySession secondSession = StockReplaySession.ready(
			SERVICE_DATE.plusDays(1), SERVICE_DATE.minusDays(2), AFTER_HOURS.plusDays(1), AFTER_HOURS);
		LocalDateTime firstSourceTime = AFTER_HOURS.minusHours(7);
		StockReplayPriceDto readyClosed = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, firstSession.getSourceTradingDate(), new BigDecimal("71000"),
			firstSourceTime, firstSession);
		StockReplayPriceDto readyOpen = new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, secondSession.getSourceTradingDate(), new BigDecimal("82000"),
			AFTER_HOURS.minusHours(6), secondSession);
		StockReplayPriceDto notReadyClosed = new StockReplayPriceDto(
			false, StockMarketStatus.CLOSED, null, null, null, null);
		List<Long> instrumentIds = List.of(1L, 2L, 3L);
		when(delegate.getCurrentPrices(instrumentIds))
			.thenReturn(List.of(readyClosed, readyOpen, notReadyClosed));

		List<StockReplayPriceDto> results = provider(true).getCurrentPrices(instrumentIds);

		assertThat(results).hasSize(3);
		StockReplayPriceDto forced = results.get(0);
		assertThat(forced.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(forced.price()).isEqualByComparingTo("71000");
		assertThat(forced.sourceTradingDate()).isEqualTo(firstSession.getSourceTradingDate());
		assertThat(forced.sourceTime()).isEqualTo(firstSourceTime);
		assertThat(forced.replaySession()).isSameAs(firstSession);
		assertThat(results.get(1)).isSameAs(readyOpen);
		assertThat(results.get(2)).isSameAs(notReadyClosed);
		verify(delegate).getCurrentPrices(instrumentIds);
	}

	@Test
	void getCurrentPricesKeepsClosedQuotesAndPayloadUnchangedWhenForceFlagIsOff() {
		StockReplaySession firstSession = readySession();
		StockReplaySession secondSession = StockReplaySession.ready(
			SERVICE_DATE.plusDays(1), SERVICE_DATE.minusDays(2), AFTER_HOURS.plusDays(1), AFTER_HOURS);
		StockReplayPriceDto first = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, firstSession.getSourceTradingDate(), new BigDecimal("71000"),
			AFTER_HOURS.minusHours(7), firstSession);
		StockReplayPriceDto second = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, secondSession.getSourceTradingDate(), new BigDecimal("82000"),
			AFTER_HOURS.minusHours(6), secondSession);
		List<Long> instrumentIds = List.of(1L, 2L);
		when(delegate.getCurrentPrices(instrumentIds)).thenReturn(List.of(first, second));

		List<StockReplayPriceDto> results = provider(false).getCurrentPrices(instrumentIds);

		assertThat(results).containsExactly(first, second);
		assertThat(results.get(0)).isSameAs(first);
		assertThat(results.get(1)).isSameAs(second);
		assertThat(results).extracting(StockReplayPriceDto::marketStatus)
			.containsExactly(StockMarketStatus.CLOSED, StockMarketStatus.CLOSED);
		verify(delegate).getCurrentPrices(instrumentIds);
	}

	private LocalForcedOpenStockPriceProvider provider(boolean forceMarketOpen) {
		return new LocalForcedOpenStockPriceProvider(delegate, stockReplaySessionRepository, clock, forceMarketOpen);
	}

	private static StockReplaySession readySession() {
		return StockReplaySession.ready(SERVICE_DATE, SERVICE_DATE.minusDays(1), AFTER_HOURS, AFTER_HOURS);
	}

	private static StockReplaySession failedSession() {
		return StockReplaySession.failed(SERVICE_DATE, null, AFTER_HOURS, "검증 완료된 거래일 데이터를 찾지 못했습니다.",
			AFTER_HOURS);
	}
}
