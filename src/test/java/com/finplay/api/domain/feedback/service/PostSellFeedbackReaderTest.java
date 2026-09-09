package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class PostSellFeedbackReaderTest {

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SELL_SERVICE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);

	private final PostSellFeedbackContextReader postSellFeedbackContextReader = mock(
		PostSellFeedbackContextReader.class);

	private final StockPostSellFeedbackReader stockPostSellFeedbackReader = mock(StockPostSellFeedbackReader.class);

	private final CryptoPostSellFeedbackReader cryptoPostSellFeedbackReader = mock(CryptoPostSellFeedbackReader.class);

	private final PostSellFeedbackReader postSellFeedbackReader = new PostSellFeedbackReader(
		postSellFeedbackContextReader, stockPostSellFeedbackReader, cryptoPostSellFeedbackReader);


	@Test
	@DisplayName("주식 매도 체결은 주식 조립에 위임하고 그 응답을 그대로 돌려준다")
	void delegatesStockSellTradeToTheStockReader() {
		Trade trade = stockSellTrade();
		SellAllocationSummaryDto allocation = allocation();
		givenContext(trade, allocation);
		PostSellFeedbackResponse assembled = assembledResponse();
		when(stockPostSellFeedbackReader.read(trade, allocation)).thenReturn(assembled);

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		verify(stockPostSellFeedbackReader).read(trade, allocation);
		assertThat(response).isSameAs(assembled);
		verifyNoInteractions(cryptoPostSellFeedbackReader);
	}

	@Test
	@DisplayName("코인 매도 체결은 400이 아니라 코인 조립에 위임하고 그 응답을 그대로 돌려준다")
	void delegatesCryptoSellTradeToTheCryptoReaderInsteadOfRejectingIt() {
		Trade cryptoTrade = cryptoSellTrade();
		SellAllocationSummaryDto allocation = allocation();
		givenContext(cryptoTrade, allocation);
		PostSellFeedbackResponse assembled = assembledResponse();
		when(cryptoPostSellFeedbackReader.read(cryptoTrade, allocation)).thenReturn(assembled);

		PostSellFeedbackResponse response = postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		verify(cryptoPostSellFeedbackReader).read(cryptoTrade, allocation);
		assertThat(response).isSameAs(assembled);
		verifyNoInteractions(stockPostSellFeedbackReader);
	}


	@Test
	@DisplayName("컨텍스트를 그 회원·체결 id로 먼저 읽고 그 뒤에 조립에 넘긴다")
	void loadsTheContextBeforeAssembling() {
		Trade trade = stockSellTrade();
		SellAllocationSummaryDto allocation = allocation();
		givenContext(trade, allocation);

		postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID);

		InOrder inOrder = inOrder(postSellFeedbackContextReader, stockPostSellFeedbackReader);
		inOrder.verify(postSellFeedbackContextReader).loadContext(USER_ID, SELL_TRADE_ID);
		inOrder.verify(stockPostSellFeedbackReader).read(trade, allocation);
	}


	@ParameterizedTest
	@EnumSource(value = ErrorCode.class, names = {"NOT_FOUND", "FORBIDDEN", "VALIDATION_ERROR"})
	@DisplayName("컨텍스트 로드가 던진 404·403·400을 그대로 전파하고 조립을 시작하지 않는다")
	void propagatesContextLoadFailuresWithoutAssembling(ErrorCode errorCode) {
		when(postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(errorCode));

		assertThatThrownBy(() -> postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode()).isEqualTo(errorCode));

		verifyNoInteractions(stockPostSellFeedbackReader, cryptoPostSellFeedbackReader);
	}


	@Test
	@DisplayName("PostSellFeedbackReader에는 클래스·read 어디에도 @Transactional이 없다")
	void neverWrapsTheOrchestrationInATransaction() throws Exception {
		assertThat(PostSellFeedbackReader.class.getAnnotation(Transactional.class)).isNull();
		assertThat(PostSellFeedbackReader.class.getAnnotation(jakarta.transaction.Transactional.class)).isNull();

		Method read = PostSellFeedbackReader.class.getDeclaredMethod("read", Long.class, Long.class);
		assertThat(read.getAnnotation(Transactional.class)).isNull();
		assertThat(read.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
	}


	private void givenContext(Trade trade, SellAllocationSummaryDto allocation) {
		when(postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.thenReturn(new PostSellFeedbackContext(trade, allocation));
	}

	private static PostSellFeedbackResponse assembledResponse() {
		return new PostSellFeedbackResponse(
			SELL_TRADE_ID, INSTRUMENT_ID, "005930", "삼성전자", null, null, null, null, null, 0L, null, null, null,
			true, null, null, null, null, null, null, null, null, List.of(), null, null, null, null, null, null);
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

	private static Trade stockSellTrade() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		LocalDateTime resolvedAt = LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(8, 40));
		return trade(
			instrument,
			StockReplaySession.ready(SELL_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt),
			new BigDecimal("68500"), 685_000L, 102L, -15_207L);
	}

	private static Trade cryptoSellTrade() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1"), 5_000L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return trade(instrument, null, new BigDecimal("100000000"), 100_000_000L, 50_000L, 1_000L);
	}

	private static Trade trade(
		Instrument instrument,
		StockReplaySession session,
		BigDecimal price,
		long amount,
		long fee,
		Long realizedPnl) {
		LocalDateTime executedAt = LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME);
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, OrderSide.SELL, price, new BigDecimal("10"), amount, fee,
			realizedPnl, executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}
}
