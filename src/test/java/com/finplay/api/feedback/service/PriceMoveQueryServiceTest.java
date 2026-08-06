// PriceMoveQueryService의 코인 분기("최근 24시간" 조회, FEED-006)를 mock 의존성으로 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.StockReplayService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 노출 게이트(§C-5의 revealTime 비교)와 재생세션 판정은 이 분기가 아예 참조하지 않는다는 것이 spec §C-5
// "카드(코인) — 없음"이다 — 그래서 stockReplayService에 대한 verifyNoInteractions가 이 파일의 핵심 단정이다.
// 실제 24시간 경계·정렬은 실 DB 위에서만 의미가 있어 PriceMoveEventRepositoryTest가 맡는다(ADR-0003).
class PriceMoveQueryServiceTest {

	private static final Long INSTRUMENT_ID = 42L;

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 15, 0, 0);

	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	private final FeedbackCryptoProperties cryptoProperties = new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35);

	private final PriceMoveQueryService service = new PriceMoveQueryService(
		instrumentService,
		stockReplayService,
		priceMoveEventRepository,
		priceMoveEventSourceRepository,
		cryptoProperties,
		Clock.fixed(NOW.atZone(KST).toInstant(), KST));

	private static Instrument cryptoInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 50_000_000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}

	private PriceMoveEvent cryptoEvent(Long id, LocalDateTime occurredAt) {
		PriceMoveEvent event = PriceMoveEvent.createCrypto(
			cryptoInstrument(), occurredAt, new BigDecimal("0.031000"), new BigDecimal("3.4000"),
			"대형 거래소 상장 소식이 있었습니다.", NarrativeSource.TEMPLATE, NOW);
		ReflectionTestUtils.setField(event, "id", id);
		return event;
	}

	@Test
	@DisplayName("코인 종목이고 카드가 0건이면 originTradeDate는 null이고 moves는 빈 배열이다")
	void returnsNullOriginTradeDateAndEmptyMovesWhenNoCryptoCardExists() {
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(cryptoInstrument());
		when(priceMoveEventRepository.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			eq(INSTRUMENT_ID), eq(Market.CRYPTO), any(), any()))
			.thenReturn(List.of());

		PriceMoveListResponse response = service.getPriceMoves(INSTRUMENT_ID);

		assertThat(response).isEqualTo(PriceMoveListResponse.of(null, List.of()));
	}

	@Test
	@DisplayName("코인 종목은 (now - 24시간, now)를 조회 창으로 리포지토리를 부른다")
	void queriesTheRepositoryWithExactly24HourWindowEndingAtNow() {
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(cryptoInstrument());
		when(priceMoveEventRepository.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			eq(INSTRUMENT_ID), eq(Market.CRYPTO), eq(NOW.minusHours(24)), eq(NOW)))
			.thenReturn(List.of());

		service.getPriceMoves(INSTRUMENT_ID);

		verify(priceMoveEventRepository).findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			INSTRUMENT_ID, Market.CRYPTO, NOW.minusHours(24), NOW);
	}

	@Test
	@DisplayName("카드가 있으면 ofCrypto로 매핑하고 근거를 붙이며 originTradeDate는 여전히 null이다")
	void mapsCryptoCardsWithSourcesAndKeepsOriginTradeDateNull() {
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 5, 14, 58, 0);
		PriceMoveEvent event = cryptoEvent(7L, occurredAt);
		Instrument instrument = cryptoInstrument();
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(priceMoveEventRepository.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			eq(INSTRUMENT_ID), eq(Market.CRYPTO), any(), any()))
			.thenReturn(List.of(event));

		MarketNewsItem news = MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "대형 거래소 상장", "coindesk.com",
			"https://news.example.test/crypto/1", occurredAt.minusMinutes(10), NOW);
		PriceMoveEventSource source = PriceMoveEventSource.of(event, news);
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(anyList()))
			.thenReturn(List.of(source));

		PriceMoveListResponse response = service.getPriceMoves(INSTRUMENT_ID);

		assertThat(response.originTradeDate()).isNull();
		assertThat(response.moves()).singleElement().satisfies(move -> {
			assertThat(move.id()).isEqualTo(7L);
			assertThat(move.windowEnd()).isEqualTo(occurredAt);
			// rolling-window-minutes=5 — ofCrypto의 windowStart 계산이 그대로 서비스 응답에 실리는지 본다.
			assertThat(move.windowStart()).isEqualTo(occurredAt.minusMinutes(5));
			assertThat(move.sources()).hasSize(1);
			assertThat(move.sources().get(0).title()).isEqualTo("대형 거래소 상장");
		});
	}

	// §C-5 "카드(코인) — 없음" — 코인 분기는 노출 게이트를 아예 참조하지 않는다. 재생세션 판정(주식 게이트가
	// 쓰는 것)을 부르면 이 단정이 깨진다.
	@Test
	@DisplayName("코인 분기는 재생세션(주식 노출 게이트)을 전혀 참조하지 않는다")
	void neverConsultsStockReplaySessionForCryptoInstrument() {
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(cryptoInstrument());
		when(priceMoveEventRepository.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			eq(INSTRUMENT_ID), eq(Market.CRYPTO), any(), any()))
			.thenReturn(List.of());

		service.getPriceMoves(INSTRUMENT_ID);

		verifyNoInteractions(stockReplayService);
	}

	@Test
	@DisplayName("코인 분기는 revealTime 조건이 있는 주식 전용 파인더를 부르지 않는다")
	void neverCallsTheStockRevealTimeFinderForCryptoInstrument() {
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(cryptoInstrument());
		when(priceMoveEventRepository.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			eq(INSTRUMENT_ID), eq(Market.CRYPTO), any(), any()))
			.thenReturn(List.of());

		service.getPriceMoves(INSTRUMENT_ID);

		verify(priceMoveEventRepository, never())
			.findByInstrumentIdAndOriginTradeDateAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				any(), any(), any());
	}
}
