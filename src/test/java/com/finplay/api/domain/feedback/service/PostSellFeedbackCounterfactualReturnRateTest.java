package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.dto.response.Counterfactuals;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.service.StockCandleDto;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
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

	private static final BigDecimal AT_FIRST_MOVE_PRICE = new BigDecimal("23334");
	private static final BigDecimal AT_HOLD_HIGH_PRICE = new BigDecimal("43334");
	private static final BigDecimal AT_CLOSE_PRICE = new BigDecimal("36667");

	private static final BigDecimal QUANTITY = new BigDecimal("1");

	private static final long ALLOCATED_COST = 9_000L;
	private static final long ALLOCATED_BUY_FEE = 1_000L;
	private static final long BUY_BASIS = ALLOCATED_COST + ALLOCATED_BUY_FEE;

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

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

		assertThat(counterfactuals.atClose().price()).isEqualByComparingTo(AT_CLOSE_PRICE);
		assertThat(counterfactuals.atHoldHigh().price()).isEqualByComparingTo(AT_HOLD_HIGH_PRICE);
		assertThat(counterfactuals.atFirstMoveAfterBuy().price()).isEqualByComparingTo(AT_FIRST_MOVE_PRICE);

		assertThat(counterfactuals.atClose().returnRate()).isEqualTo(new BigDecimal("2.6662"));
		assertThat(counterfactuals.atClose().returnRate()).isNotEqualTo(new BigDecimal("2.6661"));

		assertThat(counterfactuals.atHoldHigh().returnRate()).isEqualTo(new BigDecimal("3.3328"));
		assertThat(counterfactuals.atHoldHigh().returnRate()).isNotEqualTo(new BigDecimal("3.3327"));

		assertThat(counterfactuals.atFirstMoveAfterBuy().returnRate()).isEqualTo(new BigDecimal("1.3331"));
		assertThat(counterfactuals.atFirstMoveAfterBuy().returnRate()).isNotEqualTo(new BigDecimal("1.3330"));
	}

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
		Account account = Account.create(user, Market.STOCK, executedAt);
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
