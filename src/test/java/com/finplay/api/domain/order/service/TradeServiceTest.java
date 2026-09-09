package com.finplay.api.domain.order.service;

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

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.TradeListItemResponse;
import com.finplay.api.domain.order.dto.response.TradeListResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
		when(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(10L, OrderSide.SELL))
			.thenReturn(true);
		when(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(20L, OrderSide.SELL))
			.thenReturn(false);

		assertThat(tradeService.hasSellHistory(10L)).isTrue();
		assertThat(tradeService.hasSellHistory(20L)).isFalse();
		verify(tradeRepository, never())
			.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(any(), eq(OrderSide.BUY));
	}

	@Test
	void hasAnySellHistoryAsksRepositoryWithSellSideAndReturnsBothOutcomes() {
		when(
			tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(OrderSide.SELL, Market.STOCK))
			.thenReturn(true);
		when(tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(OrderSide.SELL,
			Market.CRYPTO))
			.thenReturn(false);

		assertThat(tradeService.hasAnySellHistory(Market.STOCK)).isTrue();
		assertThat(tradeService.hasAnySellHistory(Market.CRYPTO)).isFalse();
		verify(tradeRepository, never())
			.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(eq(OrderSide.BUY), any());
	}

	@Test
	void findEarliestFilledBuyTradeMatchingMatchesQuantityRegardlessOfScale() {
		LocalDateTime after = NOW.minusDays(1);
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
	void findLatestFilledBuyTradeMatchingPicksLastQuantityMatchInRepositoryOrder() {
		LocalDateTime after = NOW.minusDays(1);
		Trade nonMatching = buyTrade(10L, NOW.minusHours(3), new BigDecimal("5"));
		Trade earliestMatching = buyTrade(20L, NOW.minusHours(2), new BigDecimal("3"));
		Trade laterMatching = buyTrade(30L, NOW.minusHours(1), new BigDecimal("3"));
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of(nonMatching, earliestMatching, laterMatching));

		Optional<Trade> result = tradeService.findLatestFilledBuyTradeMatching(
			USER_ID, 100L, new BigDecimal("3"), after);

		assertThat(result).isPresent();
		assertThat(result.get()).isSameAs(laterMatching);
	}

	@Test
	void findLatestFilledBuyTradeMatchingMatchesQuantityRegardlessOfScale() {
		LocalDateTime after = NOW.minusDays(1);
		Trade differentScaleTrade = buyTrade(1L, NOW.minusHours(1), new BigDecimal("0.10000000"));
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of(differentScaleTrade));

		Optional<Trade> result = tradeService.findLatestFilledBuyTradeMatching(
			USER_ID, 100L, new BigDecimal("0.1"), after);

		assertThat(result).isPresent();
		assertThat(result.get()).isSameAs(differentScaleTrade);
	}

	@Test
	void findLatestFilledBuyTradeMatchingReturnsEmptyWhenNoTradeMatchesQuantity() {
		LocalDateTime after = NOW.minusDays(1);
		Trade mismatchedTrade = buyTrade(1L, NOW.minusHours(1), new BigDecimal("5"));
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.BUY, after))
			.thenReturn(List.of(mismatchedTrade));

		Optional<Trade> result = tradeService.findLatestFilledBuyTradeMatching(
			USER_ID, 100L, new BigDecimal("3"), after);

		assertThat(result).isEmpty();
	}

	@Test
	void findEarliestFilledSellTradeAfterPicksEarliestSellTradeInRepositoryOrderRegardlessOfQuantity() {
		LocalDateTime after = NOW.minusHours(1);
		Trade earliestSell = sellTrade(20L, NOW.minusMinutes(50), new BigDecimal("1"));
		Trade laterSell = sellTrade(30L, NOW.minusMinutes(10), new BigDecimal("3"));
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.SELL, after))
			.thenReturn(List.of(earliestSell, laterSell));

		Optional<Trade> result = tradeService.findEarliestFilledSellTradeAfter(USER_ID, 100L, after);

		assertThat(result).isPresent();
		assertThat(result.get()).isSameAs(earliestSell);
	}

	@Test
	void findEarliestFilledSellTradeAfterReturnsEmptyWhenRepositoryHasNoCandidates() {
		LocalDateTime after = NOW.minusHours(1);
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.SELL, after))
			.thenReturn(List.of());

		Optional<Trade> result = tradeService.findEarliestFilledSellTradeAfter(USER_ID, 100L, after);

		assertThat(result).isEmpty();
	}

	@Test
	void findEarliestFilledSellTradeAfterQueriesRepositoryWithSellSideAndGivenAfterBoundary() {
		LocalDateTime after = NOW.minusHours(1);
		when(tradeRepository.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.SELL, after))
			.thenReturn(List.of());

		tradeService.findEarliestFilledSellTradeAfter(USER_ID, 100L, after);

		verify(tradeRepository).findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.SELL, after);
		verify(tradeRepository, never())
			.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
				USER_ID, 100L, OrderSide.BUY, after);
	}

	@Test
	void findPracticePriceSessionIdReturnsSessionIdFromRepository() {
		when(tradeRepository.findPracticePriceSessionIdByTradeId(30L)).thenReturn(Optional.of(7L));

		Optional<Long> result = tradeService.findPracticePriceSessionId(30L);

		assertThat(result).contains(7L);
	}

	@Test
	void findPracticePriceSessionIdReturnsEmptyWhenTradeHasNoPracticeSession() {
		when(tradeRepository.findPracticePriceSessionIdByTradeId(31L)).thenReturn(Optional.empty());

		Optional<Long> result = tradeService.findPracticePriceSessionId(31L);

		assertThat(result).isEmpty();
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

	@Test
	void summarizePracticeRunReturnsBuyTradePriceItselfWhenThereIsExactlyOneBuy() {
		BigDecimal price = new BigDecimal("10932.45600000");
		when(tradeRepository.findFilledPracticeRunTrades(77L, 2L))
			.thenReturn(List.of(practiceTrade(1L, OrderSide.BUY, price, new BigDecimal("3.00000000"),
				32_797L, 4L, null)));

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 2L);

		assertThat(summary.averageBuyPrice()).isEqualByComparingTo(price);
		assertThat(summary.averageBuyPrice().scale()).isEqualTo(8);
		assertThat(summary.averageSellPrice()).isNull();
		assertThat(summary.realizedPnl()).isNull();
		assertThat(summary.soldBuyBasis()).isNull();
	}

	@Test
	void summarizePracticeRunWeightsAveragePricesByQuantityNotByTradeCount() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("1"), 100L, 0L, null),
			practiceTrade(2L, OrderSide.BUY, new BigDecimal("130"), new BigDecimal("3"), 390L, 0L, null),
			practiceTrade(3L, OrderSide.SELL, new BigDecimal("200"), new BigDecimal("1"), 200L, 0L, 90L),
			practiceTrade(4L, OrderSide.SELL, new BigDecimal("240"), new BigDecimal("3"), 720L, 0L, 330L)));

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 1L);

		assertThat(summary.averageBuyPrice()).isEqualByComparingTo(new BigDecimal("122.50000000"));
		assertThat(summary.averageSellPrice()).isEqualByComparingTo(new BigDecimal("230.00000000"));
		assertThat(summary.buyQuantity()).isEqualByComparingTo(new BigDecimal("4"));
		assertThat(summary.sellQuantity()).isEqualByComparingTo(new BigDecimal("4"));
		assertThat(summary.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void summarizePracticeRunSumsRealizedPnlAndInvertsSoldBuyBasisFromLedgerAmounts() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 3L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("1500"), 150_000L, 22L, null),
			practiceTrade(2L, OrderSide.SELL, new BigDecimal("110"), new BigDecimal("1000"), 110_000L, 16L, 9_984L),
			practiceTrade(3L, OrderSide.SELL, new BigDecimal("110"), new BigDecimal("500"), 55_000L, 8L, 4_992L)));

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 3L);

		assertThat(summary.realizedPnl()).isEqualTo(14_976L);
		assertThat(summary.soldBuyBasis()).isEqualTo(150_000L);
	}

	@Test
	void summarizePracticeRunKeepsPartialSellQuantitiesAndPricesSeparate() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("10"), 1_000L, 0L, null),
			practiceTrade(2L, OrderSide.SELL, new BigDecimal("120"), new BigDecimal("4"), 480L, 0L, 80L)));

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 1L);

		assertThat(summary.buyQuantity()).isEqualByComparingTo(new BigDecimal("10"));
		assertThat(summary.sellQuantity()).isEqualByComparingTo(new BigDecimal("4"));
		assertThat(summary.remainingQuantity()).isEqualByComparingTo(new BigDecimal("6"));
		assertThat(summary.averageBuyPrice()).isEqualByComparingTo(new BigDecimal("100"));
		assertThat(summary.averageSellPrice()).isEqualByComparingTo(new BigDecimal("120"));
		assertThat(summary.realizedPnl()).isEqualTo(80L);
		assertThat(summary.soldBuyBasis()).isEqualTo(400L);
	}

	@Test
	void summarizePracticeRunLeavesSellSideNullWhenRunHasBuyOnly() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("10"), 1_000L, 1L, null)));

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 1L);

		assertThat(summary.sellQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(summary.firstSellTrade()).isNull();
		assertThat(summary.averageSellPrice()).isNull();
		assertThat(summary.realizedPnl()).isNull();
		assertThat(summary.soldBuyBasis()).isNull();
	}

	@Test
	void summarizePracticeRunDropsBothPnlFieldsWhenAnySellHasNoLedgerRealizedPnl() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("10"), 1_000L, 0L, null),
			practiceTrade(2L, OrderSide.SELL, new BigDecimal("120"), new BigDecimal("4"), 480L, 0L, 80L),
			practiceTrade(3L, OrderSide.SELL, new BigDecimal("120"), new BigDecimal("2"), 240L, 0L, null)));

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 1L);

		assertThat(summary.realizedPnl()).isNull();
		assertThat(summary.soldBuyBasis()).isNull();
		assertThat(summary.averageSellPrice()).isEqualByComparingTo(new BigDecimal("120"));
		assertThat(summary.sellQuantity()).isEqualByComparingTo(new BigDecimal("6"));
	}

	@Test
	void summarizePracticeRunReturnsAllNullPricesWhenRunHasNoTradesYet() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of());

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 1L);

		assertThat(summary.buyQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(summary.sellQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(summary.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(summary.averageBuyPrice()).isNull();
		assertThat(summary.averageSellPrice()).isNull();
		assertThat(summary.realizedPnl()).isNull();
		assertThat(summary.soldBuyBasis()).isNull();
	}

	@Test
	void summarizePracticeRunPicksFirstSellInRepositoryOrderAsFirstSellTrade() {
		Trade earlierSell = practiceTrade(
			2L, OrderSide.SELL, new BigDecimal("120"), new BigDecimal("4"), 480L, 0L, 80L);
		Trade laterSell = practiceTrade(
			3L, OrderSide.SELL, new BigDecimal("130"), new BigDecimal("6"), 780L, 0L, 180L);
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("10"), 1_000L, 0L, null),
			earlierSell, laterSell));

		PracticeRunTradeSummaryDto summary = tradeService.summarizePracticeRun(77L, 1L);

		assertThat(summary.firstSellTrade()).isSameAs(earlierSell);
	}

	@Test
	void summarizePracticeRunEntriesSplitsTheLedgerAtEachEntryBuyTrade() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("10"), 1_000L, 0L, null),
			practiceTrade(2L, OrderSide.SELL, new BigDecimal("97"), new BigDecimal("10"), 970L, 0L, -30L),
			practiceTrade(3L, OrderSide.BUY, new BigDecimal("90"), new BigDecimal("10"), 900L, 0L, null),
			practiceTrade(4L, OrderSide.SELL, new BigDecimal("105"), new BigDecimal("10"), 1_050L, 0L, 150L)));

		List<PracticeRunTradeSummaryDto> entries = tradeService.summarizePracticeRunEntries(
			77L, 1L, List.of(1L, 3L));

		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).averageBuyPrice()).isEqualByComparingTo(new BigDecimal("100"));
		assertThat(entries.get(0).averageSellPrice()).isEqualByComparingTo(new BigDecimal("97"));
		assertThat(entries.get(0).realizedPnl()).isEqualTo(-30L);
		assertThat(entries.get(0).firstSellTrade().getId()).isEqualTo(2L);
		assertThat(entries.get(1).averageBuyPrice()).isEqualByComparingTo(new BigDecimal("90"));
		assertThat(entries.get(1).averageSellPrice()).isEqualByComparingTo(new BigDecimal("105"));
		assertThat(entries.get(1).realizedPnl()).isEqualTo(150L);
		assertThat(entries.get(1).firstSellTrade().getId()).isEqualTo(4L);
	}

	@Test
	void summarizePracticeRunEntriesGivesEveryLaterTradeToTheLastEntry() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("10"), 1_000L, 0L, null),
			practiceTrade(2L, OrderSide.SELL, new BigDecimal("120"), new BigDecimal("4"), 480L, 0L, 80L),
			practiceTrade(3L, OrderSide.SELL, new BigDecimal("130"), new BigDecimal("6"), 780L, 0L, 180L)));

		List<PracticeRunTradeSummaryDto> entries = tradeService.summarizePracticeRunEntries(77L, 1L, List.of(1L));

		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).sellQuantity()).isEqualByComparingTo(new BigDecimal("10"));
		assertThat(entries.get(0).realizedPnl()).isEqualTo(260L);
		assertThat(entries.get(0).firstSellTrade().getId()).isEqualTo(2L);
	}

	@Test
	void summarizePracticeRunEntriesGivesEveryEarlierTradeToTheFirstEntry() {
		when(tradeRepository.findFilledPracticeRunTrades(77L, 1L)).thenReturn(List.of(
			practiceTrade(1L, OrderSide.SELL, new BigDecimal("100"), new BigDecimal("2"), 200L, 0L, 20L),
			practiceTrade(2L, OrderSide.BUY, new BigDecimal("100"), new BigDecimal("10"), 1_000L, 0L, null),
			practiceTrade(3L, OrderSide.SELL, new BigDecimal("97"), new BigDecimal("10"), 970L, 0L, -30L)));

		List<PracticeRunTradeSummaryDto> entries = tradeService.summarizePracticeRunEntries(77L, 1L, List.of(2L));

		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).sellQuantity()).isEqualByComparingTo(new BigDecimal("12"));
		assertThat(entries.get(0).realizedPnl()).isEqualTo(-10L);
	}

	@Test
	void summarizePracticeRunEntriesRejectsBoundariesThatAreNotAscending() {
		assertThatThrownBy(() -> tradeService.summarizePracticeRunEntries(77L, 1L, List.of(3L, 1L)))
			.isInstanceOf(IllegalArgumentException.class);
		verify(tradeRepository, never()).findFilledPracticeRunTrades(77L, 1L);
	}

	@Test
	void summarizePracticeRunEntriesReadsNothingWhenThereIsNoEntry() {
		assertThat(tradeService.summarizePracticeRunEntries(77L, 1L, List.of())).isEmpty();
		verify(tradeRepository, never()).findFilledPracticeRunTrades(77L, 1L);
	}

	private static Trade practiceTrade(
		Long id, OrderSide side, BigDecimal price, BigDecimal quantity, long amount, long fee, Long realizedPnl) {
		Order order = Order.create(
			testUser(), account(), stockInstrument(), side, OrderType.MARKET, quantity,
			"idem-practice-" + id, "h".repeat(64), NOW);
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			side, price, quantity, amount, fee, realizedPnl, NOW, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
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

	private static Trade sellTrade(Long id, LocalDateTime executedAt, BigDecimal quantity) {
		Order order = Order.create(
			testUser(), account(), stockInstrument(), OrderSide.SELL, OrderType.MARKET, quantity,
			"idem-key-sell-" + id, "h".repeat(64), NOW);
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.SELL, new BigDecimal("110"), quantity, 330L, 1L, 30L, executedAt, NOW);
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

	private static com.finplay.api.domain.market.entity.StockReplaySession stockSession() {
		return com.finplay.api.domain.market.entity.StockReplaySession.ready(
			NOW.toLocalDate(), NOW.toLocalDate(), NOW, NOW);
	}

	private static com.finplay.api.domain.market.entity.Instrument stockInstrument() {
		return com.finplay.api.domain.market.entity.Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Account account() {
		return Account.create(testUser(), Market.STOCK, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
