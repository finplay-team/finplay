package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CryptoFeedbackBatchServiceTest {

	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final InstrumentNewsSummaryService instrumentNewsSummaryService = mock(InstrumentNewsSummaryService.class);

	private final MarketBriefingService marketBriefingService = mock(MarketBriefingService.class);

	private final Instrument bitcoin = crypto(1L, "BTC", "비트코인");

	private final Instrument ethereum = crypto(2L, "ETH", "이더리움");

	private final CryptoFeedbackBatchService service = new CryptoFeedbackBatchService(
		instrumentService, instrumentNewsSummaryService, marketBriefingService);

	private static Instrument crypto(Long id, String symbol, String name) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, symbol, name, BigDecimal.ONE, 5000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	@BeforeEach
	void setUp() {
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO))
			.thenReturn(List.of(bitcoin, ethereum));
		when(instrumentNewsSummaryService.refreshCryptoSummary(any()))
			.thenReturn(Optional.of(mock(InstrumentNewsSummary.class)));
		when(marketBriefingService.refreshCryptoBriefing())
			.thenReturn(Optional.of(mock(MarketBriefing.class)));
	}

	@Test
	@DisplayName("코인 전 종목의 요약과 시장 브리핑을 한 번씩 갱신한다")
	void refreshesEveryCryptoSummaryAndTheMarketBriefing() {
		service.refreshCryptoFeedback();

		verify(instrumentNewsSummaryService).refreshCryptoSummary(bitcoin);
		verify(instrumentNewsSummaryService).refreshCryptoSummary(ethereum);
		verify(marketBriefingService).refreshCryptoBriefing();
	}

	@Test
	@DisplayName("주식 종목은 대상이 아니다 — 코인만 조회한다")
	void neverTouchesStockInstruments() {
		service.refreshCryptoFeedback();

		verify(instrumentService).getRealInstrumentEntities(Market.CRYPTO);
		verify(instrumentService, never()).getRealInstrumentEntities(Market.STOCK);
	}

	@Test
	@DisplayName("주식 경로의 배치를 부르지 않는다")
	void neverDelegatesToTheStockBatchPath() {
		service.refreshCryptoFeedback();

		verify(instrumentNewsSummaryService, never()).generateStockSummary(any(), any(), any());
		verify(marketBriefingService, never()).generateStockBriefing(any());
	}

	@Test
	@DisplayName("한 종목의 요약 갱신이 실패해도 나머지 종목과 브리핑이 계속된다")
	void continuesWithOtherInstrumentsAndTheBriefingWhenOneSummaryThrows() {
		when(instrumentNewsSummaryService.refreshCryptoSummary(bitcoin))
			.thenThrow(new IllegalStateException("LLM 호출 실패"));

		assertThatCode(() -> service.refreshCryptoFeedback()).doesNotThrowAnyException();

		verify(instrumentNewsSummaryService).refreshCryptoSummary(ethereum);
		verify(marketBriefingService).refreshCryptoBriefing();
	}

	@Test
	@DisplayName("브리핑 갱신이 실패해도 배치가 정상 종료하고 요약은 이미 갱신돼 있다")
	void finishesNormallyWhenTheBriefingRefreshThrows() {
		when(marketBriefingService.refreshCryptoBriefing())
			.thenThrow(new IllegalStateException("브리핑 LLM 호출 실패"));

		assertThatCode(() -> service.refreshCryptoFeedback()).doesNotThrowAnyException();

		verify(instrumentNewsSummaryService).refreshCryptoSummary(bitcoin);
		verify(instrumentNewsSummaryService).refreshCryptoSummary(ethereum);
	}

	@Test
	@DisplayName("모든 종목이 empty()를 돌려줘도 배치가 정상 종료한다")
	void finishesNormallyWhenEveryRefreshReturnsEmpty() {
		when(instrumentNewsSummaryService.refreshCryptoSummary(any())).thenReturn(Optional.empty());
		when(marketBriefingService.refreshCryptoBriefing()).thenReturn(Optional.empty());

		assertThatCode(() -> service.refreshCryptoFeedback()).doesNotThrowAnyException();

		verify(marketBriefingService).refreshCryptoBriefing();
	}
}
