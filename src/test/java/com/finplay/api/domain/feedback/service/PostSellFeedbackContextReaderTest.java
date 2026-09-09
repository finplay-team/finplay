package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.service.SellAllocationQueryService;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class PostSellFeedbackContextReaderTest {

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SELL_SERVICE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);

	private final TradeService tradeService = mock(TradeService.class);

	private final SellAllocationQueryService sellAllocationQueryService = mock(SellAllocationQueryService.class);

	private final PostSellFeedbackContextReader postSellFeedbackContextReader = new PostSellFeedbackContextReader(
		tradeService, sellAllocationQueryService);


	@Test
	@DisplayName("본인 매도 체결이면 그 체결과 배분 요약을 한 묶음으로 돌려준다")
	void returnsTheOwnedSellTradeWithItsAllocationSummary() {
		Trade trade = sellTrade();
		SellAllocationSummaryDto allocation = allocation();
		givenOwnedTrade(trade);
		givenAllocation(allocation);

		PostSellFeedbackContext context = postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID);

		assertThat(context.trade()).isSameAs(trade);
		assertThat(context.allocation()).isSameAs(allocation);
	}

	@Test
	@DisplayName("코인 매도 체결도 400 없이 배분을 읽어 그대로 돌려준다")
	void loadsCryptoSellTradeWithoutRejectingIt() {
		Trade cryptoTrade = cryptoSellTrade();
		givenOwnedTrade(cryptoTrade);
		givenAllocation(allocation());

		PostSellFeedbackContext context = postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID);

		assertThat(context.trade()).isSameAs(cryptoTrade);
		verify(sellAllocationQueryService).getSellAllocationSummary(SELL_TRADE_ID);
	}


	@Test
	@DisplayName("매수 체결이면 400 VALIDATION_ERROR이고 배분을 읽지 않는다")
	void rejectsBuyTradeWithValidationErrorWithoutReadingAllocations() {
		givenOwnedTrade(buyTrade());

		assertThatThrownBy(() -> postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(sellAllocationQueryService);
	}

	@Test
	@DisplayName("체결이 없으면 404 NOT_FOUND가 그대로 전파되고 배분을 읽지 않는다")
	void propagatesNotFoundWithoutReadingAllocations() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verifyNoInteractions(sellAllocationQueryService);
	}

	@Test
	@DisplayName("타인 체결이면 400이 아니라 403 FORBIDDEN이 그대로 전파되고 배분을 읽지 않는다")
	void propagatesForbiddenBeforeTheSideCheck() {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN)
				.isNotEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(sellAllocationQueryService);
	}

	@Test
	@DisplayName("체결을 먼저 읽고 그 뒤에 그 매도 체결 id로만 배분을 조회한다")
	void readsTheOwnedTradeBeforeQueryingTheAllocationOfThatTradeOnly() {
		givenOwnedTrade(sellTrade());
		givenAllocation(allocation());

		postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID);

		InOrder inOrder = inOrder(tradeService, sellAllocationQueryService);
		inOrder.verify(tradeService).getOwnedTrade(USER_ID, SELL_TRADE_ID);
		inOrder.verify(sellAllocationQueryService).getSellAllocationSummary(SELL_TRADE_ID);
	}


	private void givenOwnedTrade(Trade trade) {
		when(tradeService.getOwnedTrade(USER_ID, SELL_TRADE_ID)).thenReturn(trade);
	}

	private void givenAllocation(SellAllocationSummaryDto allocation) {
		when(sellAllocationQueryService.getSellAllocationSummary(any())).thenReturn(allocation);
	}

	private static SellAllocationSummaryDto allocation() {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(SELL_SERVICE_DATE, BUY_TIME),
			ORIGIN_TRADE_DATE,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE));
	}

	private static Trade sellTrade() {
		return trade(
			stockInstrument(), session(), OrderSide.SELL, new BigDecimal("68500"), 685_000L, 102L, -15_207L,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
	}

	private static Trade buyTrade() {
		return trade(
			stockInstrument(), session(), OrderSide.BUY, new BigDecimal("70000"), 700_000L, 105L, null,
			LocalDateTime.of(SELL_SERVICE_DATE, BUY_TIME));
	}

	private static Trade cryptoSellTrade() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1"), 5_000L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		return trade(
			instrument, null, OrderSide.SELL, new BigDecimal("100000000"), 100_000_000L, 50_000L, 1_000L,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
	}

	private static Trade trade(
		Instrument instrument,
		StockReplaySession session,
		OrderSide side,
		BigDecimal price,
		long amount,
		long fee,
		Long realizedPnl,
		LocalDateTime executedAt) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, side, OrderType.MARKET, new BigDecimal("10"), "idem-key", "h".repeat(64),
			executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, side, price, new BigDecimal("10"), amount, fee, realizedPnl,
			executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}

	private static Instrument stockInstrument() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}

	private static StockReplaySession session() {
		LocalDateTime resolvedAt = LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(8, 40));
		return StockReplaySession.ready(SELL_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt);
	}
}
