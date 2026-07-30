// force-market-open 플래그와 재생세션 준비상태 조합에 따른 시장상태 덮어쓰기·위임 동작을 검증하는 단위 테스트
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.PreparationStatus;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockReplaySessionRepository;
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
	// 2026-07-29(수) 22:00 — 평일이지만 장외 시간이라 재생세션이 READY여도 실제 판정은 CLOSED다.
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
		// 플래그가 꺼져 있으면 세션을 조회하지도 않는다 — "데이터는 READY인데 시장은 CLOSED"인 정직한 상태가 유지된다.
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
		when(delegate.getCandles(INSTRUMENT_ID, null, null)).thenReturn(List.of());
		LocalForcedOpenStockPriceProvider provider = provider(true);

		assertThat(provider.getCurrentPrice(INSTRUMENT_ID)).isSameAs(price);
		assertThat(provider.getCurrentPrices(List.of(INSTRUMENT_ID))).containsExactly(price);
		assertThat(provider.getCandles(INSTRUMENT_ID, null, null)).isEmpty();
		// 시세·캔들 경로는 시장상태와 무관하게 그대로 위임되어야 한다 — 데코레이터가 값을 만들어 내지 않는다.
		verify(delegate).getCurrentPrice(INSTRUMENT_ID);
		verify(delegate).getCurrentPrices(any());
		verify(delegate).getCandles(INSTRUMENT_ID, null, null);
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
