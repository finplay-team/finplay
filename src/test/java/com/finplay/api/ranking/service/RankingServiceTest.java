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
		when(rankingStore.topN(eq(Market.STOCK), eq(11))).thenReturn(List.of());

		rankingService.getRankings(Market.STOCK, 0);

		// limit 10으로 클램핑된 뒤, 경계 동점 확인을 위해 limit+1(11)개를 요청한다.
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
		// 동점 그룹(score=100)의 두 계좌: userId가 낮은 쪽(10)이 먼저 나와야 한다.
		// window 크기(3) <= limit(10)이라 경계 동점 병합 경로는 타지 않는다.
		Account tiedLowUserId = account(1L, Market.STOCK, 100L, 10L, "alice");
		Account tiedHighUserId = account(2L, Market.STOCK, 100L, 20L, "bob");
		Account thirdPlace = account(3L, Market.STOCK, 50L, 5L, "carol");

		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(
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
		verify(rankingStore, never()).findAllAtScore(any(), anyLong());
	}

	// PR #196 리뷰 지적(차단 2): topN(limit)만 가져오면 경계에 걸친 동점자가 Redis 멤버 문자열 사전순으로
	// 잘려 정책(userId 오름차순)과 다른 사람이 노출될 수 있다. limit=1인데 동점 2명이 있는 경계 상황을 재현한다.
	@Test
	void getRankingsMergesFullTieGroupWhenBoundaryScoreIsTiedAcrossWindowEdge() {
		// Redis 자체 사전순(문자열 tie-break)이라면 accountId=2(userId=99)가 먼저 나왔을 상황을 시뮬레이션한다.
		Account higherUserId = account(2L, Market.STOCK, 100L, 99L, "bob");
		Account lowerUserId = account(1L, Market.STOCK, 100L, 1L, "alice");

		when(rankingStore.topN(Market.STOCK, 2)).thenReturn(List.of(
			new RankingEntryDto(2L, 100L),
			new RankingEntryDto(1L, 100L)));
		when(rankingStore.findAllAtScore(Market.STOCK, 100L)).thenReturn(List.of(
			new RankingEntryDto(2L, 100L),
			new RankingEntryDto(1L, 100L)));
		when(accountService.findAllByIdInFetchUser(any()))
			.thenReturn(List.of(higherUserId, lowerUserId));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, 1);

		// 정책(userId 오름차순)에 따라 alice(userId=1)가 limit=1 안에 남아야 한다.
		assertThat(response.content()).containsExactly(new RankingListItemResponse(1, "alice", 100L));
		verify(rankingStore, times(1)).findAllAtScore(Market.STOCK, 100L);
	}

	// 경계에 동점이 없으면 findAllAtScore를 호출하지 않아야 한다(불필요한 Redis 호출 방지, 리뷰 지적 최적화 요구).
	@Test
	void getRankingsDoesNotCallFindAllAtScoreWhenNoBoundaryTie() {
		Account first = account(1L, Market.STOCK, 200L, 1L, "alice");
		Account second = account(2L, Market.STOCK, 100L, 2L, "bob");

		when(rankingStore.topN(Market.STOCK, 2)).thenReturn(List.of(
			new RankingEntryDto(1L, 200L),
			new RankingEntryDto(2L, 100L)));
		when(accountService.findAllByIdInFetchUser(any())).thenReturn(List.of(first, second));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 200L)).thenReturn(0L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, 1);

		assertThat(response.content()).containsExactly(new RankingListItemResponse(1, "alice", 200L));
		verify(rankingStore, never()).findAllAtScore(any(), anyLong());
	}

	// PR #196 리뷰 지적(차단 1): Redis window의 accountId가 DB accountById 배치 조회 결과에 없으면(파생 데이터
	// 어긋남) NPE로 500이 나던 부분을 확인한다 — 걸러내고 나머지 항목은 정상 계산돼야 한다.
	@Test
	void getRankingsExcludesEntryWithoutMatchingDbAccountInsteadOfThrowing() {
		Account existing = account(1L, Market.STOCK, 100L, 1L, "alice");

		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(999L, 80L))); // 999는 DB에 없는 유령 accountId
		when(accountService.findAllByIdInFetchUser(List.of(1L, 999L)))
			.thenReturn(List.of(existing));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.content()).containsExactly(new RankingListItemResponse(1, "alice", 100L));
	}

	// 독립 reviewer 세션이 발견한 잔여 결함(PR #196 검증 중): 동점이 없는 경계 경로에서 window 안의 유령
	// accountId를 걸러내고 나면 유효 항목이 limit보다 적어질 수 있는데, 그 자리를 이미 "+1"로 확보해둔
	// 여유분(3번째 항목)이 보충해야 한다. limit=2, topN(3)=[alice(100), 유령(90), carol(80)]에서
	// 2번째가 유령이면 3번째 carol이 살아나 2건이 채워져야 한다(수정 전에는 1건만 남았다).
	@Test
	void getRankingsBackfillsFromSpareEntryWhenBoundaryWindowContainsGhostAccount() {
		Account alice = account(1L, Market.STOCK, 100L, 1L, "alice");
		Account carol = account(3L, Market.STOCK, 80L, 3L, "carol");

		when(rankingStore.topN(Market.STOCK, 3)).thenReturn(List.of(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(999L, 90L), // 999는 DB에 없는 유령 accountId — 경계(2번째)에 위치
			new RankingEntryDto(3L, 80L)));
		when(accountService.findAllByIdInFetchUser(any())).thenReturn(List.of(alice, carol));
		when(rankingStore.countStrictlyGreater(Market.STOCK, 100L)).thenReturn(0L);
		// 유령(990점)은 필터링으로 응답에는 안 나오지만 실제 Redis ZSET에는 여전히 남아있어
		// countStrictlyGreater(80)에는 alice(100)·유령(90) 둘 다 카운트된다(기존에 문서화된 별개의 한계).
		when(rankingStore.countStrictlyGreater(Market.STOCK, 80L)).thenReturn(2L);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, 2);

		assertThat(response.content()).containsExactly(
			new RankingListItemResponse(1, "alice", 100L),
			new RankingListItemResponse(3, "carol", 80L));
		verify(rankingStore, never()).findAllAtScore(any(), anyLong());
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
