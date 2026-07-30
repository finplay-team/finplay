// TradeService.getMyTrades의 커서 페이지네이션 판정과 체결 내역 매핑을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.response.TradeListItemResponse;
import com.finplay.api.order.dto.response.TradeListResponse;
import com.finplay.api.order.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TradeServiceTest {

	private static final Long USER_ID = 1L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final AccountService accountService = mock(AccountService.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);

	private final TradeService tradeService = new TradeService(accountService, tradeRepository);

	@Test
	void getMyTradesReturnsNoNextPageWhenFetchedCountIsAtMostLimit() {
		Account account = account();
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		Trade trade1 = buyTrade(3L, NOW.minusMinutes(1));
		Trade trade2 = buyTrade(2L, NOW.minusMinutes(2));
		when(tradeRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(3)))
			.thenReturn(List.of(trade1, trade2));

		TradeListResponse response = tradeService.getMyTrades(USER_ID, Market.STOCK, null, 2);

		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		assertThat(response.content()).hasSize(2);
	}

	@Test
	void getMyTradesSetsNextCursorFromLimitthItemWhenFetchedCountExceedsLimit() {
		Account account = account();
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		Trade trade1 = buyTrade(30L, NOW.minusMinutes(1));
		Trade trade2 = buyTrade(20L, NOW.minusMinutes(2));
		Trade trade3 = buyTrade(10L, NOW.minusMinutes(3));
		int limit = 2;
		when(tradeRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(limit + 1)))
			.thenReturn(List.of(trade1, trade2, trade3));

		TradeListResponse response = tradeService.getMyTrades(USER_ID, Market.STOCK, null, limit);

		assertThat(response.hasNext()).isTrue();
		// 다음 페이지 있음(3건 조회) 시 nextCursor는 반환 페이지(limit=2건)의 마지막 항목인 trade2 기준이어야 한다 — 초과 조회된 trade3 기준이면 버그.
		assertThat(response.nextCursor()).isEqualTo(TradeCursor.encode(trade2));
		assertThat(response.content()).hasSize(2);
	}

	@Test
	void getMyTradesMapsBuyTradeWithNullRealizedPnlAndSellTradeWithRealizedPnlValue() {
		Account account = account();
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		Trade buyTrade = buyTrade(100L, NOW.minusMinutes(1));
		Trade sellTrade = sellTrade(200L, NOW.minusMinutes(2), 5_000L);
		when(tradeRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(3)))
			.thenReturn(List.of(buyTrade, sellTrade));

		TradeListResponse response = tradeService.getMyTrades(USER_ID, Market.STOCK, null, 2);

		assertThat(response.content()).hasSize(2);
		TradeListItemResponse buyResponse = response.content().get(0);
		assertThat(buyResponse.tradeId()).isEqualTo(100L);
		assertThat(buyResponse.side()).isEqualTo("BUY");
		assertThat(buyResponse.price()).isEqualByComparingTo(new BigDecimal("100"));
		assertThat(buyResponse.quantity()).isEqualByComparingTo(new BigDecimal("3"));
		assertThat(buyResponse.amount()).isEqualTo(300L);
		assertThat(buyResponse.fee()).isEqualTo(1L);
		assertThat(buyResponse.realizedPnl()).isNull();
		assertThat(buyResponse.executedAt()).isEqualTo(NOW.minusMinutes(1));

		TradeListItemResponse sellResponse = response.content().get(1);
		assertThat(sellResponse.tradeId()).isEqualTo(200L);
		assertThat(sellResponse.side()).isEqualTo("SELL");
		assertThat(sellResponse.realizedPnl()).isEqualTo(5_000L);
	}

	@Test
	void getMyTradesPropagatesExceptionThrownByCorruptedCursor() {
		Account account = account();
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);

		assertThatThrownBy(() -> tradeService.getMyTrades(USER_ID, Market.STOCK, "garbage", 20))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(tradeRepository, never()).findByAccountIdWithCursor(any(), any(), any(), anyInt());
	}

	@Test
	void getMyTradesReturnsEmptyContentWhenAccountHasNoTrades() {
		Account account = account();
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(account);
		when(tradeRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		TradeListResponse response = tradeService.getMyTrades(USER_ID, Market.STOCK, null, 20);

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	private static Trade buyTrade(Long id, LocalDateTime executedAt) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static Trade sellTrade(Long id, LocalDateTime executedAt, long realizedPnl) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), OrderSide.SELL, new BigDecimal("110"),
			new BigDecimal("3"), 330L, 1L, realizedPnl, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static Order order() {
		return Order.create(
			testUser(),
			account(),
			stockInstrument(),
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			"idem-key",
			"h".repeat(64),
			NOW);
	}

	private static com.finplay.api.market.domain.Instrument stockInstrument() {
		return com.finplay.api.market.domain.Instrument.create(
			com.finplay.api.market.domain.Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Account account() {
		return Account.create(testUser(), Market.STOCK, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
