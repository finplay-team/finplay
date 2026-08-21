// 코인 요약·브리핑 매시 배치의 대상 선정과 실패 격리를 검증하는 단위 테스트다.
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

// 이 클래스는 오케스트레이션만 한다 — 재생성 판정·UPSERT·범위는 각 서비스의 단위 테스트와
// CryptoFeedbackBatchIntegrationTest가 맡는다. 여기서는 "누구를 부르고, 하나가 터지면 어떻게 되는가"만 본다.
//
// 격리 검증은 메서드 경계를 spy로 갈아끼우지 않고 본문이 실제로 부르는 협력자를 특정 종목에서만 던지게 한다
// (FeedbackBatchServiceTest의 continuesWithOtherInstrumentsWhenDetectionThrows와 같은 형태).
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

	// 주식 종목을 함께 돌리면 코인 규칙(UPSERT·ROLLING_24H)이 주식 행에 적용된다 — 배치 ⑤가 깨진다.
	@Test
	@DisplayName("주식 종목은 대상이 아니다 — 코인만 조회한다")
	void neverTouchesStockInstruments() {
		service.refreshCryptoFeedback();

		verify(instrumentService).getRealInstrumentEntities(Market.CRYPTO);
		verify(instrumentService, never()).getRealInstrumentEntities(Market.STOCK);
	}

	// 코인은 재생 시간축이 없어 주식의 READY 확인이 성립하지 않는다 — 세션을 보면 재생세션이 없는 날
	// 코인 갱신이 통째로 멈춘다.
	@Test
	@DisplayName("주식 경로의 배치를 부르지 않는다")
	void neverDelegatesToTheStockBatchPath() {
		service.refreshCryptoFeedback();

		verify(instrumentNewsSummaryService, never()).generateStockSummary(any(), any(), any());
		verify(marketBriefingService, never()).generateStockBriefing(any());
	}

	// 매시 도는 배치라 한 종목의 LLM 호출 실패는 드문 일이 아니다. 격리가 없으면 그 시각 갱신이 통째로
	// 날아가고 다음 시각까지 화면이 낡은 채로 남는다 — 예외도 없이 로그 한 줄만 남는다.
	@Test
	@DisplayName("한 종목의 요약 갱신이 실패해도 나머지 종목과 브리핑이 계속된다")
	void continuesWithOtherInstrumentsAndTheBriefingWhenOneSummaryThrows() {
		when(instrumentNewsSummaryService.refreshCryptoSummary(bitcoin))
			.thenThrow(new IllegalStateException("LLM 호출 실패"));

		assertThatCode(() -> service.refreshCryptoFeedback()).doesNotThrowAnyException();

		verify(instrumentNewsSummaryService).refreshCryptoSummary(ethereum);
		verify(marketBriefingService).refreshCryptoBriefing();
	}

	// 브리핑은 호출 1건이라 요약과 분리해 격리한다 — 순서상 마지막이라 여기서 던지면 배치가 예외로 끝난다.
	@Test
	@DisplayName("브리핑 갱신이 실패해도 배치가 정상 종료하고 요약은 이미 갱신돼 있다")
	void finishesNormallyWhenTheBriefingRefreshThrows() {
		when(marketBriefingService.refreshCryptoBriefing())
			.thenThrow(new IllegalStateException("브리핑 LLM 호출 실패"));

		assertThatCode(() -> service.refreshCryptoFeedback()).doesNotThrowAnyException();

		verify(instrumentNewsSummaryService).refreshCryptoSummary(bitcoin);
		verify(instrumentNewsSummaryService).refreshCryptoSummary(ethereum);
	}

	// 새 기사가 없어 empty()가 오는 것이 정상 경로다(배치 ⑩) — 그때도 브리핑 단계까지 간다.
	@Test
	@DisplayName("모든 종목이 empty()를 돌려줘도 배치가 정상 종료한다")
	void finishesNormallyWhenEveryRefreshReturnsEmpty() {
		when(instrumentNewsSummaryService.refreshCryptoSummary(any())).thenReturn(Optional.empty());
		when(marketBriefingService.refreshCryptoBriefing()).thenReturn(Optional.empty());

		assertThatCode(() -> service.refreshCryptoFeedback()).doesNotThrowAnyException();

		verify(marketBriefingService).refreshCryptoBriefing();
	}
}
