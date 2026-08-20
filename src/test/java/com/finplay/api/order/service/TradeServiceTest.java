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

	// 아래는 이슈 #339 tasks.md 6번 통합 테스트 작업 중 발견한 회귀 수정 — 샘플 종목 chain 재도전용
	// findLatestFilledBuyTradeMatching(수량 정규화 비교 + 가장 최신 체결 선택, findEarliestFilledBuyTradeMatching과
	// 대칭)을 검증한다.

	@Test
	void findLatestFilledBuyTradeMatchingPicksLastQuantityMatchInRepositoryOrder() {
		LocalDateTime after = NOW.minusDays(1);
		// repository는 executedAt ASC, id ASC로 이미 정렬해 반환한다 — 서비스는 그 순서에서 수량이 일치하는
		// 마지막(가장 최신) 항목을 골라야 한다. earliestMatching(id=20)이 아니라 laterMatching(id=30)이 선택돼야
		// 만료된 샘플 chain의 최초 매수가 아니라 재도전 매수를 anchor로 쓸 수 있다.
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

	// 아래는 031-tutorial-sandbox-instruments 매도 chain 해석이 쓰는
	// findEarliestFilledSellTradeAfter(수량 무관 + buyTrade 이후 가장 이른 체결 선택)을 검증한다.

	@Test
	void findEarliestFilledSellTradeAfterPicksEarliestSellTradeInRepositoryOrderRegardlessOfQuantity() {
		LocalDateTime after = NOW.minusHours(1);
		// repository는 executedAt ASC, id ASC로 이미 정렬해 반환한다는 계약이다(쿼리 메서드명). 서비스는 그
		// 순서의 첫 항목을 그대로 골라야 하며, buyTrade 수량(예: 3)과 다른 수량(부분 매도, 1)도 그대로
		// 채택돼야 한다 — 수량 일치를 요구하는 findEarliestFilledBuyTradeMatching과 달리 이 메서드는 수량
		// 비교 로직 자체가 없어야 한다.
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

		// 매수 이전 SELL을 무시하는 경계(after=buyTrade.executedAt)를 그대로 넘기는지, SELL로 조회하지(BUY로
		// 잘못 부르지 않는지) 확인한다. PENDING/CANCELLED 상태는 별도로 걸러낼 필요가 없다 — trades 테이블은
		// 체결 결과만 영속하므로(TradeRepository 41행 주석, Trade 엔티티에 status 필드 자체가 없음) 이 조회
		// 결과는 이미 전부 FILLED 체결이다.
		verify(tradeRepository).findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
			USER_ID, 100L, OrderSide.SELL, after);
		verify(tradeRepository, never())
			.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
				USER_ID, 100L, OrderSide.BUY, after);
	}

	// 030 holding 관찰 세션 역추적(이슈 #321)이 쓰는 위임 — buyTradeId로 practicePriceSessionId를 조회한다.

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

	// 아래는 이슈 #421에서 summarizePracticeRun이 수량 합계에 더해 집계하게 된 이번 실행 매매 결과 —
	// 가중평균 체결가, 원장 실현손익 합, 수익률 분모(soldBuyBasis) 역산을 검증한다.

	@Test
	void summarizePracticeRunReturnsBuyTradePriceItselfWhenThereIsExactlyOneBuy() {
		// 매수 체결이 1건이면 나눗셈이 원래 단가를 그대로 돌려줘야 practice_risk_snapshots.entry_price와 어긋나지
		// 않는다(같은 scale 8) — 여기서 반올림이 끼면 진행 조회의 buyPrice가 riskSnapshot.entryPrice와 미세하게
		// 달라져 화면 두 곳이 다른 매수가를 보여준다.
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
		// BUY 100원 1주 + 130원 3주. 수량 가중평균은 122.5이고 단순 산술평균이면 115다 — 두 값이 갈라지는
		// 입력이라야 "가중"이 실제로 걸려 있는지 확인할 수 있다.
		// SELL도 마찬가지로 200원 1주 + 240원 3주 → 230, 산술평균이면 220이다.
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
		// soldBuyBasis는 trade_allocations를 다시 읽지 않고 (amount - fee) - realizedPnl로 역산한다.
		// sell1: (110000 - 16) - 9984 = 100000, sell2: (55000 - 8) - 4992 = 50000 → 합 150000.
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
		// 부분 매도라도 매도 평균가는 팔린 체결만 보고, 매수 평균가는 매수 체결만 본다 — 섞이면 안 된다.
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
		// realized_pnl이 아직 채워지지 않은 SELL이 하나라도 섞이면 합계는 "덜 더해진 값"이라 노출하면 안 된다.
		// 반쪽 손익을 그대로 내보내면 화면 금액이 조용히 틀린다. 매도 평균가는 원장 단가라 그대로 남는다.
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

	// summarizePracticeRun 전용 — attempt·run 귀속은 repository 쿼리가 걸러주므로 여기서는 side·단가·수량·
	// 금액·수수료·실현손익만 지정한 체결을 만든다.
	// 041 SCENARIO-019b — 진입별 대조 배열은 같은 실행 세대의 체결을 진입 매수 체결 id로 쪼갠 합을 쓴다.
	@Test
	void summarizePracticeRunEntriesSplitsTheLedgerAtEachEntryBuyTrade() {
		// 1번 진입: 100원 10주 매수 → 97원 10주 손절. 2번 진입: 90원 10주 매수 → 105원 10주 익절.
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

	// 마지막 진입은 상한이 없다 — 그 뒤 체결(부분 매도의 나머지 등)이 통째로 빠지면 금액이 틀린다.
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
		// 진입 안에서도 첫 매도를 쓴다 — 실행 전체(tradeResult)와 같은 규칙을 진입 범위로 좁힌 것이다.
		assertThat(entries.get(0).firstSellTrade().getId()).isEqualTo(2L);
	}

	// 매수 전에는 진입 자체가 없다 — 원장을 읽지 않고 빈 목록을 돌려준다.
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
