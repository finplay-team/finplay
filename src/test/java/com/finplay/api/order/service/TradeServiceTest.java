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
import java.util.Optional;
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

	@Test
	void getOwnedTradeReturnsTradeWhenCallerIsOwner() {
		Trade trade = tradeOwnedBy(5L, USER_ID, NOW);
		when(tradeRepository.findById(5L)).thenReturn(Optional.of(trade));

		Trade result = tradeService.getOwnedTrade(USER_ID, 5L);

		assertThat(result).isSameAs(trade);
	}

	@Test
	void getOwnedTradeThrowsNotFoundWhenTradeDoesNotExist() {
		when(tradeRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> tradeService.getOwnedTrade(USER_ID, 99L))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void getOwnedTradeThrowsForbiddenWhenTradeOwnedByAnotherUser() {
		Trade trade = tradeOwnedBy(6L, 999L, NOW);
		when(tradeRepository.findById(6L)).thenReturn(Optional.of(trade));

		assertThatThrownBy(() -> tradeService.getOwnedTrade(USER_ID, 6L))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));
	}

	// 아래 3개는 랭킹 재구성·status 판정이 쓰는 위임(이슈 #279)이다. 위임 자체는 얇지만 이 테스트가 지키는 건
	// 위임 코드가 아니라 **인자로 넘기는 OrderSide.SELL**이다 — 여기가 BUY로 바뀌거나 realized_pnl 기반 조회로
	// 갈아타면 랭킹 대상 집합이 통째로 달라지는데, repository 슬라이스 테스트는 자기 인자를 스스로 넘기므로
	// 그 회귀를 잡지 못한다. ArgumentCaptor 대신 eq()로 stub해 "SELL로 부르지 않으면 기본값이 반환된다"로 드러낸다.
	@Test
	void getSoldAccountIdsQueriesSellSideOnlyForRequestedMarket() {
		when(tradeRepository.findDistinctAccountIdsBySideAndMarket(OrderSide.SELL, Market.CRYPTO))
			.thenReturn(List.of(11L, 12L));

		assertThat(tradeService.getSoldAccountIds(Market.CRYPTO)).containsExactly(11L, 12L);
		verify(tradeRepository).findDistinctAccountIdsBySideAndMarket(OrderSide.SELL, Market.CRYPTO);
		verify(tradeRepository, never()).findDistinctAccountIdsBySideAndMarket(OrderSide.BUY, Market.CRYPTO);
	}

	@Test
	void hasSellHistoryAsksRepositoryWithSellSideAndReturnsBothOutcomes() {
		when(tradeRepository.existsByAccountIdAndSide(10L, OrderSide.SELL)).thenReturn(true);
		when(tradeRepository.existsByAccountIdAndSide(20L, OrderSide.SELL)).thenReturn(false);

		assertThat(tradeService.hasSellHistory(10L)).isTrue();
		assertThat(tradeService.hasSellHistory(20L)).isFalse();
		verify(tradeRepository, never()).existsByAccountIdAndSide(any(), eq(OrderSide.BUY));
	}

	@Test
	void hasAnySellHistoryAsksRepositoryWithSellSideAndReturnsBothOutcomes() {
		when(tradeRepository.existsBySideAndAccountMarket(OrderSide.SELL, Market.STOCK)).thenReturn(true);
		when(tradeRepository.existsBySideAndAccountMarket(OrderSide.SELL, Market.CRYPTO)).thenReturn(false);

		assertThat(tradeService.hasAnySellHistory(Market.STOCK)).isTrue();
		assertThat(tradeService.hasAnySellHistory(Market.CRYPTO)).isFalse();
		verify(tradeRepository, never()).existsBySideAndAccountMarket(eq(OrderSide.BUY), any());
	}

	// 아래는 026-market-order-practice-tutorial 2단계 chain 해석이 쓰는
	// findEarliestFilledBuyTradeMatching(수량 정규화 비교 + 가장 이른 체결 선택)을 검증한다.

	@Test
	void findEarliestFilledBuyTradeMatchingMatchesQuantityRegardlessOfScale() {
		LocalDateTime after = NOW.minusDays(1);
		// intention.quantity()는 "0.1", 실제 체결 수량은 scale이 다른 "0.10000000" — BigDecimal.compareTo 기준
		// 정규화 비교로 같은 값으로 인정돼야 한다(020의 scale 무관 규칙).
		Trade differentScaleTrade = buyTrade(1L, NOW.minusHours(1), new BigDecimal("0.10000000"));
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of(differentScaleTrade));

		Optional<Trade> result = tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, new BigDecimal("0.1"), after);

		assertThat(result).isPresent();
		assertThat(result.get()).isSameAs(differentScaleTrade);
	}

	@Test
	void findEarliestFilledBuyTradeMatchingReturnsEmptyWhenNoTradeMatchesQuantity() {
		LocalDateTime after = NOW.minusDays(1);
		Trade mismatchedTrade = buyTrade(1L, NOW.minusHours(1), new BigDecimal("5"));
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of(mismatchedTrade));

		Optional<Trade> result = tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, new BigDecimal("3"), after);

		assertThat(result).isEmpty();
	}

	@Test
	void findEarliestFilledBuyTradeMatchingReturnsEmptyWhenRepositoryHasNoCandidates() {
		LocalDateTime after = NOW.minusDays(1);
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of());

		Optional<Trade> result = tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, new BigDecimal("3"), after);

		assertThat(result).isEmpty();
	}

	@Test
	void findEarliestFilledBuyTradeMatchingPicksFirstQuantityMatchInRepositoryOrder() {
		LocalDateTime after = NOW.minusDays(1);
		// repository는 executedAt ASC, id ASC로 이미 정렬해 반환한다는 계약이다(쿼리 메서드명). 서비스는 그
		// 순서를 유지한 채 수량이 일치하는 첫 항목을 고른다 — 앞선 수량 불일치 항목을 건너뛰고 더 이른
		// 매칭 항목(id=20)을 골라야 하며 그 뒤 나오는 또 다른 매칭 항목(id=30)을 고르면 버그다.
		Trade nonMatching = buyTrade(10L, NOW.minusHours(3), new BigDecimal("5"));
		Trade earliestMatching = buyTrade(20L, NOW.minusHours(2), new BigDecimal("3"));
		Trade laterMatching = buyTrade(30L, NOW.minusHours(1), new BigDecimal("3"));
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of(nonMatching, earliestMatching, laterMatching));

		Optional<Trade> result = tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, new BigDecimal("3"), after);

		assertThat(result).isPresent();
		assertThat(result.get()).isSameAs(earliestMatching);
	}

	@Test
	void findEarliestFilledBuyTradeMatchingQueriesRepositoryWithBuySideAndGivenAfterBoundary() {
		LocalDateTime after = NOW.minusDays(1);
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of());

		tradeService.findEarliestFilledBuyTradeMatching(USER_ID, 100L, new BigDecimal("3"), after);

		verify(tradeRepository).findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after);
	}

	private static Trade buyTrade(Long id, LocalDateTime executedAt, BigDecimal quantity) {
		Order order = Order.create(
			testUser(), account(), stockInstrument(), OrderSide.BUY, OrderType.MARKET, quantity,
			"idem-key-" + id, "h".repeat(64), NOW);
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.BUY, new BigDecimal("100"), quantity, 300L, 1L, null, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	// getOwnedTrade 전용 — 소유자(user.id)를 직접 지정해 본인/타인 판정을 검증하기 위한 체결을 만든다.
	private static Trade tradeOwnedBy(Long tradeId, Long ownerUserId, LocalDateTime executedAt) {
		User owner = testUser();
		ReflectionTestUtils.setField(owner, "id", ownerUserId);
		Account ownerAccount = Account.create(owner, Market.STOCK, NOW);
		Order order = Order.create(
			owner, ownerAccount, stockInstrument(), OrderSide.BUY, OrderType.MARKET, new BigDecimal("3"),
			"idem-key", "h".repeat(64), NOW);
		Trade trade = Trade.of(
			order, ownerAccount, stockInstrument(), stockSession(),
			OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", tradeId);
		return trade;
	}

	private static Trade buyTrade(Long id, LocalDateTime executedAt) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static Trade sellTrade(Long id, LocalDateTime executedAt, long realizedPnl) {
		Order order = order();
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.SELL, new BigDecimal("110"),
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

	private static com.finplay.api.market.domain.StockReplaySession stockSession() {
		return com.finplay.api.market.domain.StockReplaySession.ready(
			NOW.toLocalDate(), NOW.toLocalDate(), NOW, NOW);
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
