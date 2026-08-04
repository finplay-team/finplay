// mock RankingStore/AccountService로 RankingService의 limit 클램핑·공동 순위 보정을 검증하는 단위 테스트다.
package com.finplay.api.ranking.service;

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

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.ranking.dto.RankingEntryDto;
import com.finplay.api.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.ranking.dto.response.RankingListResponse;
import com.finplay.api.ranking.store.RankingStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RankingServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 0, 0);

	private final RankingStore rankingStore = mock(RankingStore.class);
	private final AccountService accountService = mock(AccountService.class);

	private final RankingService rankingService = new RankingService(rankingStore, accountService);

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
		when(rankingStore.topN(eq(Market.STOCK), eq(10))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, 0);

		verify(rankingStore, times(1)).topN(Market.STOCK, 10);
	}

	@Test
	void getRankingsClampsNegativeLimitToTen() {
		when(rankingStore.topN(eq(Market.STOCK), eq(10))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, -1);

		verify(rankingStore, times(1)).topN(Market.STOCK, 10);
	}

	@Test
	void getRankingsClampsNullLimitToTen() {
		when(rankingStore.topN(eq(Market.STOCK), eq(10))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, null);

		verify(rankingStore, times(1)).topN(Market.STOCK, 10);
	}

	@Test
	void getRankingsClampsAboveMaximumLimitToFifty() {
		when(rankingStore.topN(eq(Market.STOCK), eq(50))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, 51);

		verify(rankingStore, times(1)).topN(Market.STOCK, 50);
	}

	@Test
	void getRankingsReturnsEmptyContentWhenWindowIsEmpty() {
		when(rankingStore.topN(Market.STOCK, 10)).thenReturn(List.of());

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.market()).isEqualTo("STOCK");
		assertThat(response.content()).isEmpty();
	}

	@Test
	void getRankingsProducesCoRankPatternAndCachesCountStrictlyGreaterPerUniqueScore() {
		// 동점 그룹(score=100)의 두 계좌: userId가 낮은 쪽(10)이 먼저 나와야 한다.
		Account tiedLowUserId = account(1L, Market.STOCK, 100L, 10L, "alice");
		Account tiedHighUserId = account(2L, Market.STOCK, 100L, 20L, "bob");
		Account thirdPlace = account(3L, Market.STOCK, 50L, 5L, "carol");

		when(rankingStore.topN(Market.STOCK, 10)).thenReturn(List.of(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(2L, 100L),
			new RankingEntryDto(3L, 50L)));
		when(accountService.findAllByIdInFetchUser(List.of(1L, 2L, 3L)))
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
