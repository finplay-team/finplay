package com.finplay.api.domain.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import com.finplay.api.domain.ranking.entity.RankingStatus;
import com.finplay.api.domain.ranking.store.RankingEntryDto;
import com.finplay.api.domain.ranking.store.RankingStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RankingServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 0, 0);

	private final RankingStore rankingStore = mock(RankingStore.class);
	private final AccountService accountService = mock(AccountService.class);
	private final TradeService tradeService = mock(TradeService.class);

	private final RankingService rankingService = new RankingService(rankingStore, accountService, tradeService);

	@Test
	void refreshScoreDoesNothingWhenAccountNotFound() {
		when(accountService.findByIdOrEmpty(999L)).thenReturn(Optional.empty());

		assertThatCode(() -> rankingService.refreshScore(999L)).doesNotThrowAnyException();

		verify(rankingStore, never()).addScoreWithRetry(any(), any(), anyLong());
	}

	@Test
	void refreshScoreAddsScoreWhenAccountFound() {
		Account account = account(1L, market(), 5_000L, 10L, "trader");
		when(accountService.findByIdOrEmpty(1L)).thenReturn(Optional.of(account));

		rankingService.refreshScore(1L);

		verify(rankingStore, times(1)).addScoreWithRetry(Market.STOCK, 1L, 5_000L);
	}

	@Test
	void getRankingsClampsBelowMinimumLimitToTen() {
		when(rankingStore.topN(eq(Market.STOCK), eq(11))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, 0);

		verify(rankingStore, times(1)).topN(Market.STOCK, 11);
	}

	@Test
	void getRankingsClampsNegativeLimitToTen() {
		when(rankingStore.topN(eq(Market.STOCK), eq(11))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, -1);

		verify(rankingStore, times(1)).topN(Market.STOCK, 11);
	}

	@Test
	void getRankingsClampsNullLimitToTen() {
		when(rankingStore.topN(eq(Market.STOCK), eq(11))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, null);

		verify(rankingStore, times(1)).topN(Market.STOCK, 11);
	}

	@Test
	void getRankingsClampsAboveMaximumLimitToFifty() {
		when(rankingStore.topN(eq(Market.STOCK), eq(51))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, 51);

		verify(rankingStore, times(1)).topN(Market.STOCK, 51);
	}

	@Test
	void getRankingsReturnsEmptyContentWhenWindowIsEmpty() {
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of());

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.market()).isEqualTo("STOCK");
		assertThat(response.content()).isEmpty();
	}

	@Test
	void getRankingsProducesCoRankPatternAndCachesCountStrictlyGreaterPerUniqueScore() {
		Account tiedLowUserId = account(1L, Market.STOCK, 100L, 10L, "alice");
		Account tiedHighUserId = account(2L, Market.STOCK, 100L, 20L, "bob");
		Account thirdPlace = account(3L, Market.STOCK, 50L, 5L, "carol");

		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(2L, 100L),
			new RankingEntryDto(3L, 50L)));
		when(accountService.getAccountsWithUser(List.of(1L, 2L, 3L)))
			.thenReturn(List.of(tiedLowUserId, tiedHighUserId, thirdPlace));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 50L)).thenReturn(2L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		List<RankingListItemResponse> content = response.content();
		assertThat(content).hasSize(3);
		assertThat(content.get(0)).isEqualTo(new RankingListItemResponse(1, "alice", 100L));
		assertThat(content.get(1)).isEqualTo(new RankingListItemResponse(1, "bob", 100L));
		assertThat(content.get(2)).isEqualTo(new RankingListItemResponse(3, "carol", 50L));

		verify(rankingStore, times(1)).countStrictlyGreater(Market.STOCK, 100L);
		verify(rankingStore, times(1)).countStrictlyGreater(Market.STOCK, 50L);
		verify(rankingStore, never()).findAllAtScore(any(), anyLong());
	}

	@Test
	void getRankingsMergesFullTieGroupWhenBoundaryScoreIsTiedAcrossWindowEdge() {
		Account higherUserId = account(2L, Market.STOCK, 100L, 99L, "bob");
		Account lowerUserId = account(1L, Market.STOCK, 100L, 1L, "alice");

		when(rankingStore.topN(Market.STOCK, 2)).thenReturn(List.of(
			new RankingEntryDto(2L, 100L),
			new RankingEntryDto(1L, 100L)));
		when(rankingStore.findAllAtScore(Market.STOCK, 100L)).thenReturn(List.of(
			new RankingEntryDto(2L, 100L),
			new RankingEntryDto(1L, 100L)));
		when(accountService.getAccountsWithUser(any()))
			.thenReturn(List.of(higherUserId, lowerUserId));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, 1);

		assertThat(response.content()).containsExactly(new RankingListItemResponse(1, "alice", 100L));
		verify(rankingStore, times(1)).findAllAtScore(Market.STOCK, 100L);
	}

	@Test
	void getRankingsDoesNotCallFindAllAtScoreWhenNoBoundaryTie() {
		Account first = account(1L, Market.STOCK, 200L, 1L, "alice");
		Account second = account(2L, Market.STOCK, 100L, 2L, "bob");

		when(rankingStore.topN(Market.STOCK, 2)).thenReturn(List.of(
			new RankingEntryDto(1L, 200L),
			new RankingEntryDto(2L, 100L)));
		when(accountService.getAccountsWithUser(any())).thenReturn(List.of(first, second));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 200L)).thenReturn(0L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, 1);

		assertThat(response.content()).containsExactly(new RankingListItemResponse(1, "alice", 200L));
		verify(rankingStore, never()).findAllAtScore(any(), anyLong());
	}

	@Test
	void getRankingsExcludesEntryWithoutMatchingDbAccountInsteadOfThrowing() {
		Account existing = account(1L, Market.STOCK, 100L, 1L, "alice");

		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(999L, 80L)));
		when(accountService.getAccountsWithUser(List.of(1L, 999L)))
			.thenReturn(List.of(existing));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.content()).containsExactly(new RankingListItemResponse(1, "alice", 100L));
	}

	@Test
	void getRankingsBackfillsFromSpareEntryWhenBoundaryWindowContainsGhostAccount() {
		Account alice = account(1L, Market.STOCK, 100L, 1L, "alice");
		Account carol = account(3L, Market.STOCK, 80L, 3L, "carol");

		when(rankingStore.topN(Market.STOCK, 3)).thenReturn(List.of(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(999L, 90L),
			new RankingEntryDto(3L, 80L)));
		when(accountService.getAccountsWithUser(any())).thenReturn(List.of(alice, carol));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 80L)).thenReturn(2L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, 2);

		assertThat(response.content()).containsExactly(
			new RankingListItemResponse(1, "alice", 100L),
			new RankingListItemResponse(3, "carol", 80L));
		verify(rankingStore, never()).findAllAtScore(any(), anyLong());
	}

	@Test
	void getMyRankingReturnsNullRankWithNormalNicknameAndRealizedPnlWhenNoSellHistory() {
		Account account = account(1L, Market.STOCK, 0L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(null);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.READY, null, "alice", 0L));
		verify(rankingStore, never()).countStrictlyGreater(any(), anyLong());
	}

	@Test
	void getMyRankingMapsToCorrectedRankWhenSellHistoryExists() {
		Account account = account(1L, Market.STOCK, 5_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(5_000L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 5_000L)).thenReturn(2L);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.READY, 3, "alice", 5_000L));
	}

	@Test
	void getMyRankingUsesZsetScoreNotDbRealizedPnlWhenTheyDiverge() {
		Account account = account(1L, Market.STOCK, 120_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(50_000L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 50_000L)).thenReturn(2L);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.READY, 3, "alice", 50_000L));
	}


	@Test
	void getRankingsReportsRebuildingWhenZsetIsEmptyButSellHistoryExists() {
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of());
		when(tradeService.hasAnySellHistory(Market.STOCK)).thenReturn(true);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.status()).isEqualTo(RankingStatus.REBUILDING);
		assertThat(response.content()).isEmpty();
		assertThat(response.market()).isEqualTo("STOCK");
	}

	@Test
	void getRankingsReportsReadyWhenZsetIsEmptyAndNoSellHistoryExists() {
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of());
		when(tradeService.hasAnySellHistory(Market.STOCK)).thenReturn(false);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.status()).isEqualTo(RankingStatus.READY);
		assertThat(response.content()).isEmpty();
	}

	@Test
	void getRankingsReportsReadyWithoutTouchingTradeServiceWhenZsetHasMembers() {
		Account alice = account(1L, Market.STOCK, 100L, 1L, "alice");
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(new RankingEntryDto(1L, 100L)));
		when(accountService.getAccountsWithUser(List.of(1L))).thenReturn(List.of(alice));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.status()).isEqualTo(RankingStatus.READY);
		verify(tradeService, never()).hasAnySellHistory(any());
	}

	@Test
	void getRankingsKeepsReadyWhenContentIsEmptyOnlyBecauseOfGhostFiltering() {
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(new RankingEntryDto(999L, 100L)));
		when(accountService.getAccountsWithUser(List.of(999L))).thenReturn(List.of());

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.content()).isEmpty();
		assertThat(response.status()).isEqualTo(RankingStatus.READY);
		verify(tradeService, never()).hasAnySellHistory(any());
	}

	@Test
	void getMyRankingReportsRebuildingWhenScoreIsMissingButSellHistoryExists() {
		Account account = account(1L, Market.STOCK, 5_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(null);
		when(tradeService.hasSellHistory(1L)).thenReturn(true);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response.status()).isEqualTo(RankingStatus.REBUILDING);
		assertThat(response.rank()).isNull();
		assertThat(response.nickname()).isEqualTo("alice");
	}

	@Test
	void getMyRankingReportsReadyWhenScoreIsMissingAndNoSellHistoryExists() {
		Account account = account(1L, Market.STOCK, 0L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(null);
		when(tradeService.hasSellHistory(1L)).thenReturn(false);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response.status()).isEqualTo(RankingStatus.READY);
		assertThat(response.rank()).isNull();
	}

	@Test
	void getMyRankingReportsReadyWithoutTouchingTradeServiceWhenScoreExists() {
		Account account = account(1L, Market.STOCK, 5_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(5_000L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 5_000L)).thenReturn(2L);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response.status()).isEqualTo(RankingStatus.READY);
		verify(tradeService, never()).hasSellHistory(anyLong());
	}

	@Test
	void listAndMyRankingJudgeTheSamePartialLossDifferently() {
		Account other = account(2L, Market.STOCK, 100L, 20L, "bob");
		Account mine = account(1L, Market.STOCK, 5_000L, 10L, "alice");
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(new RankingEntryDto(2L, 100L)));
		when(accountService.getAccountsWithUser(List.of(2L))).thenReturn(List.of(other));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(mine);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(null);
		when(tradeService.hasSellHistory(1L)).thenReturn(true);

		RankingListResponse list = rankingService.getRankings(Market.STOCK, null);
		MyRankingResponse me = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(list.status())
			.as("목록은 부분 유실을 감지하지 않는다 — window가 비지 않았으면 READY다")
			.isEqualTo(RankingStatus.READY);
		assertThat(me.status())
			.as("내 랭킹은 부분 유실까지 감지한다 — 내 score가 없고 내게 매도 이력이 있으면 REBUILDING이다")
			.isEqualTo(RankingStatus.REBUILDING);
	}

	@Test
	void rankIsNeverAccompaniedByRebuildingStatus() {
		Account account = account(1L, Market.STOCK, 5_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(5_000L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 5_000L)).thenReturn(2L);
		when(tradeService.hasSellHistory(1L)).thenReturn(true);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response.rank()).isNotNull();
		assertThat(response.status()).isEqualTo(RankingStatus.READY);
	}

	@Test
	void rebuildingMyRankingReportsZeroRealizedPnlEvenWhenDbValueIsNotZero() {
		Account account = account(1L, Market.STOCK, 500_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(null);
		when(tradeService.hasSellHistory(1L)).thenReturn(true);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response.status()).isEqualTo(RankingStatus.REBUILDING);
		assertThat(response.realizedPnl())
			.as("ZSET score가 없으면 DB 값을 대신 싣지 않는다 — rank와 다른 출처의 값을 섞지 않는 원칙")
			.isZero();
	}


	@Test
	void getRankingsReturnsUnavailableWhenTopNThrowsUnavailableException() {
		when(rankingStore.topN(Market.STOCK, 11))
			.thenThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"));

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response).isEqualTo(new RankingListResponse("STOCK", RankingStatus.UNAVAILABLE, List.of()));
		verify(accountService, never()).getAccountsWithUser(any());
	}

	@Test
	void getRankingsReturnsUnavailableWhenFindAllAtScoreThrowsUnavailableExceptionDuringBoundaryMerge() {
		when(rankingStore.topN(Market.STOCK, 2)).thenReturn(List.of(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(2L, 100L)));
		when(rankingStore.findAllAtScore(Market.STOCK, 100L))
			.thenThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"));

		RankingListResponse response = rankingService.getRankings(Market.STOCK, 1);

		assertThat(response.status()).isEqualTo(RankingStatus.UNAVAILABLE);
		assertThat(response.content()).isEmpty();
	}

	@Test
	void getRankingsReturnsUnavailableWhenCountStrictlyGreaterThrowsUnavailableExceptionDuringRankCalculation() {
		Account alice = account(1L, Market.STOCK, 100L, 1L, "alice");
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(new RankingEntryDto(1L, 100L)));
		when(accountService.getAccountsWithUser(List.of(1L))).thenReturn(List.of(alice));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L))
			.thenThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"));

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.status()).isEqualTo(RankingStatus.UNAVAILABLE);
		assertThat(response.content()).isEmpty();
	}

	@Test
	void getMyRankingReturnsUnavailableWithNullRankAndZeroPnlWhenScoreThrowsUnavailableException() {
		Account account = account(1L, Market.STOCK, 500_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L))
			.thenThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"));

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.UNAVAILABLE, null, "alice", 0L));
		verify(tradeService, never()).hasSellHistory(anyLong());
	}

	@Test
	void getMyRankingReturnsUnavailableWhenCountStrictlyGreaterThrowsUnavailableExceptionAfterScoreSucceeds() {
		Account account = account(1L, Market.STOCK, 5_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(5_000L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 5_000L))
			.thenThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"));

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.UNAVAILABLE, null, "alice", 0L));
	}

	private Market market() {
		return Market.STOCK;
	}

	private Account account(Long accountId, Market market, long realizedPnl, Long userId, String nickname) {
		User user = User.create(nickname + "@finplay.com", "password-hash", nickname, NOW);
		ReflectionTestUtils.setField(user, "id", userId);

		Account account = Account.create(user, market, NOW);
		ReflectionTestUtils.setField(account, "id", accountId);
		ReflectionTestUtils.setField(account, "realizedPnl", realizedPnl);
		return account;
	}
}
