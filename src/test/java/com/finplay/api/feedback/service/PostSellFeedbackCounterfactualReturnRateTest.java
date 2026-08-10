// 반사실 3종(counterfactuals)의 returnRate가 매도수수료를 FLOOR로 재계산해 산출되는지 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.dto.response.Counterfactuals;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.service.StockCandleDto;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * tasks.md 012 이슈 #212 1번의 함정 문단 — "가격·수량이 딱 나누어지는 픽스처만 쓰면 FLOOR와 일반 반올림
 * (HALF_UP 등)이 같은 결과를 낸다."
 *
 * <p><b>docs/api-contracts.md의 예시 값(buyBasis 700,105)만으로는 이 함정을 잡지 못한다.</b> 그 픽스처에서
 * 수수료 1원 차는 최종 scale-4 결과에 반영되지 않는다(1 ÷ 700,105 ≈ 0.0000014로 4번째 소수점 단위인 0.0001의
 * 1/70에도 못 미친다). 그래서 이 파일은 buyBasis를 작게(10,000) 둬서 수수료 1원 차가 반드시 4번째 소수점을 넘게
 * 만든 별도 픽스처를 쓴다 — 세 시나리오 모두 FLOOR와 HALF_UP 결과가 정확히 0.0001씩 갈린다(아래 테스트 참고).
 */
class PostSellFeedbackCounterfactualReturnRateTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime CARD_WINDOW_END = LocalTime.of(10, 0);
	private static final LocalTime HOLD_HIGH_TIME = LocalTime.of(11, 5);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	private static final LocalDateTime EXACTLY_AT_GATE = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(15, 30));

	// 세 시나리오의 매도금액 — 각각 P × 0.00015의 소수부가 .5 위(반올림하면 올라가고 FLOOR면 그대로인)인 값으로
	// 골랐다. 23,334×0.00015=3.5001, 43,334×0.00015=6.5001, 36,667×0.00015=5.50005.
	private static final BigDecimal AT_FIRST_MOVE_PRICE = new BigDecimal("23334");
	private static final BigDecimal AT_HOLD_HIGH_PRICE = new BigDecimal("43334");
	private static final BigDecimal AT_CLOSE_PRICE = new BigDecimal("36667");

	private static final BigDecimal QUANTITY = new BigDecimal("1");

	// buyBasis = allocatedCost(9,000) + allocatedBuyFee(1,000).
	private static final long ALLOCATED_COST = 9_000L;
	private static final long ALLOCATED_BUY_FEE = 1_000L;
	private static final long BUY_BASIS = ALLOCATED_COST + ALLOCATED_BUY_FEE;

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	// 이 파일은 반사실 returnRate만 본다 — 집단 비교는 stub하지 않고 Mockito 기본값(Optional.empty())으로 둔다.
	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	// 검증(404·403·400)과 배분 조회는 PostSellFeedbackContextReader로 옮겨 갔다(이슈 #282) — 이 파일은 이미
	// 검증을 마친 (trade, allocation)을 주식 조립에 그대로 넘겨 반사실 returnRate만 본다.
	private Trade trade;

	private SellAllocationSummaryDto allocation;

	@Test
	@DisplayName("반사실 3종의 returnRate가 FLOOR 수수료로 산출된다 — HALF_UP으로 반올림하면 셋 다 0.0001씩 어긋난다")
	void computesCounterfactualReturnRatesWithFlooredFeesNotRoundedFees() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(BUY_TIME, "100"),
			candle(CARD_WINDOW_END, AT_FIRST_MOVE_PRICE.toPlainString()),
			candle(HOLD_HIGH_TIME, AT_HOLD_HIGH_PRICE.toPlainString()),
			candle(SELL_TIME, "200"),
			candle(LAST_CANDLE_TIME, AT_CLOSE_PRICE.toPlainString())));
		givenCard(card(12L, LocalTime.of(9, 45), CARD_WINDOW_END));

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);
		Counterfactuals counterfactuals = response.counterfactuals();

		// 픽스처 자기검증 — 극값·카드 가격이 실제로 의도한 값으로 들어갔는지 먼저 본다. 여기서 벗어나면 아래
		// returnRate 단정이 다른 이유로 실패한 것이라 함정 검증이 무의미해진다.
		assertThat(counterfactuals.atClose().price()).isEqualByComparingTo(AT_CLOSE_PRICE);
		assertThat(counterfactuals.atHoldHigh().price()).isEqualByComparingTo(AT_HOLD_HIGH_PRICE);
		assertThat(counterfactuals.atFirstMoveAfterBuy().price()).isEqualByComparingTo(AT_FIRST_MOVE_PRICE);

		// atClose: 매도금액 36,667. FLOOR(36,667×0.00015)=FLOOR(5.50005)=5.
		// 실현손익=(36,667−5)−10,000=26,662 → 26,662÷10,000=2.6662.
		assertThat(counterfactuals.atClose().returnRate()).isEqualTo(new BigDecimal("2.6662"));
		// 반올림(HALF_UP) 구현이면 수수료가 6이 되어 26,661÷10,000=2.6661이 나간다 — 여기서 실제로 갈린다.
		assertThat(counterfactuals.atClose().returnRate()).isNotEqualTo(new BigDecimal("2.6661"));

		// atHoldHigh: 매도금액 43,334. FLOOR(43,334×0.00015)=FLOOR(6.5001)=6.
		// 실현손익=(43,334−6)−10,000=33,328 → 33,328÷10,000=3.3328.
		assertThat(counterfactuals.atHoldHigh().returnRate()).isEqualTo(new BigDecimal("3.3328"));
		assertThat(counterfactuals.atHoldHigh().returnRate()).isNotEqualTo(new BigDecimal("3.3327"));

		// atFirstMoveAfterBuy: 매도금액 23,334. FLOOR(23,334×0.00015)=FLOOR(3.5001)=3.
		// 실현손익=(23,334−3)−10,000=13,331 → 13,331÷10,000=1.3331.
		assertThat(counterfactuals.atFirstMoveAfterBuy().returnRate()).isEqualTo(new BigDecimal("1.3331"));
		assertThat(counterfactuals.atFirstMoveAfterBuy().returnRate()).isNotEqualTo(new BigDecimal("1.3330"));
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse getPostSellFeedbackAt(LocalDateTime now) {
		StockPostSellFeedbackReader reader = new StockPostSellFeedbackReader(
			stockReplayService, priceMoveEventRepository,
			new PriceMoveSourceLoader(priceMoveEventSourceRepository), priceMovePeerStatRepository,
			Clock.fixed(now.atZone(KST).toInstant(), KST));
		return reader.read(trade, allocation);
	}

	private void givenSameSessionSell() {
		trade = sellTrade();
		allocation = allocation();
	}

	private void givenCandles(List<StockCandleDto> candles) {
		when(stockReplayService.getFullDayCandles(INSTRUMENT_ID, ORIGIN_TRADE_DATE)).thenReturn(candles);
	}

	private void givenCard(PriceMoveEvent card) {
		when(priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				any(), any(), any(), any(), any()))
			.thenReturn(List.of(card));
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(any())).thenReturn(List.of());
	}

	private static StockCandleDto candle(LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		return new StockCandleDto(
			ORIGIN_TRADE_DATE, candleTime, price, price, price, price, 1_000L);
	}

	private static PriceMoveEvent card(Long id, LocalTime windowStart, LocalTime windowEnd) {
		PriceMoveEvent event = PriceMoveEvent.createStock(
			stockInstrument(),
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("0.050000"),
			new BigDecimal("3.0000"),
			"테스트 카드",
			NarrativeSource.LLM,
			windowEnd.plusMinutes(1),
			LocalDateTime.of(ORIGIN_TRADE_DATE, windowEnd));
		ReflectionTestUtils.setField(event, "id", id);
		return event;
	}

	private static SellAllocationSummaryDto allocation() {
		return new SellAllocationSummaryDto(
			new BigDecimal("9000.00000000"),
			LocalDateTime.of(TRADE_SERVICE_DATE, BUY_TIME),
			ORIGIN_TRADE_DATE,
			ALLOCATED_COST,
			ALLOCATED_BUY_FEE,
			QUANTITY,
			List.of(ORIGIN_TRADE_DATE));
	}

	private static Trade sellTrade() {
		Instrument instrument = stockInstrument();
		LocalDateTime resolvedAt = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = StockReplaySession.ready(
			TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt);
		LocalDateTime executedAt = LocalDateTime.of(TRADE_SERVICE_DATE, SELL_TIME);
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, com.finplay.api.account.domain.Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, QUANTITY, "idem-key", "h".repeat(64),
			executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, OrderSide.SELL, new BigDecimal("200"), QUANTITY, 200L, 0L, -100L,
			executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}

	private static Instrument stockInstrument() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true,
			LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}
}
