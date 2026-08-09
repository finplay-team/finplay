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
import com.finplay.api.order.service.TradeService;
import com.finplay.api.ranking.domain.RankingStatus;
import com.finplay.api.ranking.dto.RankingEntryDto;
import com.finplay.api.ranking.dto.response.MyRankingResponse;
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
		when(accountService.getAccountsWithUser(any()))
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
		when(accountService.getAccountsWithUser(any())).thenReturn(List.of(first, second));
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
		when(accountService.getAccountsWithUser(List.of(1L, 999L)))
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
		when(accountService.getAccountsWithUser(any())).thenReturn(List.of(alice, carol));
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

	// RANK-002: 매도 이력이 없으면(RankingStore.score가 null) rank만 null이고 닉네임·실현손익(0 포함)은
	// 정상 값을 반환한다 — 오류가 아니다(spec.md "비즈니스 규칙").
	@Test
	void getMyRankingReturnsNullRankWithNormalNicknameAndRealizedPnlWhenNoSellHistory() {
		Account account = account(1L, Market.STOCK, 0L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(null);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.READY, null, "alice", 0L));
		verify(rankingStore, never()).countStrictlyGreater(any(), anyLong());
	}

	// RANK-002: 매도 이력이 있으면(score가 not null) RANK-001과 동일한 보정 공식(countStrictlyGreater + 1)으로
	// rank를 계산한다 — 별도의 새 보정 공식을 만들지 않는다.
	@Test
	void getMyRankingMapsToCorrectedRankWhenSellHistoryExists() {
		Account account = account(1L, Market.STOCK, 5_000L, 10L, "alice");
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(5_000L);
		when(rankingStore.countStrictlyGreater(Market.STOCK, 5_000L)).thenReturn(2L);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.READY, 3, "alice", 5_000L));
	}

	// PR #234 리뷰 차단 반영: DB accounts.realized_pnl과 Redis ZSET score가 어긋난 경우(after-commit 반영 지연·
	// 재시도 소진 등), rank 계산에 쓴 score와 다른 값(DB 값)을 realizedPnl로 내보내면 한 응답 안에서 "이 손익,
	// 이 순위"가 서로 대응하지 않게 된다. realizedPnl도 rank와 같은 출처(ZSET score)에서 나와야 한다.
	@Test
	void getMyRankingUsesZsetScoreNotDbRealizedPnlWhenTheyDiverge() {
		Account account = account(1L, Market.STOCK, 120_000L, 10L, "alice"); // DB는 120,000이지만
		when(accountService.getAccountForWithUser(10L, Market.STOCK)).thenReturn(account);
		when(rankingStore.score(Market.STOCK, 1L)).thenReturn(50_000L); // ZSET score는 50,000으로 갈라진 상태
		when(rankingStore.countStrictlyGreater(Market.STOCK, 50_000L)).thenReturn(2L);

		MyRankingResponse response = rankingService.getMyRanking(10L, Market.STOCK);

		// rank(3)를 계산한 근거인 50,000이 realizedPnl에도 그대로 나와야 한다 — DB의 120,000이 아니다.
		assertThat(response).isEqualTo(new MyRankingResponse("STOCK", RankingStatus.READY, 3, "alice", 50_000L));
	}

	// --- 유실 상태 노출(status, 이슈 #279) ---
	// 두 엔드포인트의 판정 기준이 의도적으로 다르다: 목록은 전체 유실만, 내 랭킹은 부분 유실까지 감지한다.

	// 목록 REBUILDING: ZSET이 비었는데 그 시장에 매도 이력 계좌가 존재한다 == 유실이다.
	@Test
	void getRankingsReportsRebuildingWhenZsetIsEmptyButSellHistoryExists() {
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of());
		when(tradeService.hasAnySellHistory(Market.STOCK)).thenReturn(true);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.status()).isEqualTo(RankingStatus.REBUILDING);
		assertThat(response.content()).isEmpty();
		assertThat(response.market()).isEqualTo("STOCK");
	}

	// 목록 READY: ZSET이 비었고 매도 이력도 없다 == 정상적인 빈 랭킹이다(유실이 아니다).
	@Test
	void getRankingsReportsReadyWhenZsetIsEmptyAndNoSellHistoryExists() {
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of());
		when(tradeService.hasAnySellHistory(Market.STOCK)).thenReturn(false);

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.status()).isEqualTo(RankingStatus.READY);
		assertThat(response.content()).isEmpty();
	}

	// 정상 경로 비용 0: ZSET에 데이터가 있으면 status 판정을 위한 DB 왕복이 아예 없어야 한다.
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

	// 유령 필터링으로 content만 빈 경우는 READY를 유지한다 — ZSET 유실이 아니라 Redis/DB 불일치라는
	// 다른 상황이고, REBUILDING으로 표시하면 상태값의 의미가 흐려진다(plan.md "상태(status) 판정").
	@Test
	void getRankingsKeepsReadyWhenContentIsEmptyOnlyBecauseOfGhostFiltering() {
		when(rankingStore.topN(Market.STOCK, 11)).thenReturn(List.of(new RankingEntryDto(999L, 100L)));
		when(accountService.getAccountsWithUser(List.of(999L))).thenReturn(List.of());

		RankingListResponse response = rankingService.getRankings(Market.STOCK, null);

		assertThat(response.content()).isEmpty();
		assertThat(response.status()).isEqualTo(RankingStatus.READY);
		verify(tradeService, never()).hasAnySellHistory(any());
	}

	// 내 랭킹 REBUILDING: 내 score가 ZSET에 없는데 내 계좌에 매도 이력이 있다 == 부분 유실이든 전체 유실이든
	// 유실이다. 이 경우 rank는 여전히 null이지만 그 의미가 "매도 이력 없음"이 아니다.
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

	// 내 랭킹 READY + rank null: score도 없고 매도 이력도 없다 == 정상적인 "매도 이력 없음"이다.
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

	// score가 있으면 항상 READY이고, && 단축 평가 덕분에 매도 이력 조회 자체가 일어나지 않는다.
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

	// 두 엔드포인트의 판정 비대칭을 **하나의 상태**에서 못박는다. 위 테스트들은 목록과 내 랭킹을 각각 다른
	// 셋업으로 확인해서, "같은 순간의 같은 ZSET을 두 엔드포인트가 다르게 판정한다"는 설계 의도 자체는
	// 어느 단정에도 걸려 있지 않았다 — 한쪽 판정 기준을 다른 쪽에 맞춰 통일해 버리는 회귀(예: 목록도
	// 부분 유실을 감지하게 만들거나, 내 랭킹도 전체 유실만 보게 만드는 변경)가 통과한다.
	//
	// 상태: ZSET에는 남의 계좌(2L)만 남아 있고 내 계좌(1L)의 score는 유실됐으며 나에게는 매도 이력이 있다.
	// 목록은 window가 비지 않았으므로 READY(부분 유실 미감지), 내 랭킹은 내 score가 없고 이력이 있으므로
	// REBUILDING(부분 유실 감지)이어야 한다.
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

	// api-contracts.md의 rank × status 표에 (숫자, REBUILDING) 행이 없는 근거를 코드 쪽에서 고정한다.
	// score != null이면 rank가 채워지고 status는 항상 READY이므로 그 조합은 발생할 수 없다 — 매도 이력을
	// "있음"으로 stub해 두고도 REBUILDING이 되지 않아야 한다(그 조회 자체가 일어나지 않는다).
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

	// REBUILDING 응답의 realizedPnl은 DB accounts.realized_pnl이 아니라 0이다(api-contracts.md의 REBUILDING
	// 예시가 "realizedPnl":0인 근거). 여기 계좌는 DB 실현손익이 500,000인데도 응답은 0이어야 한다 —
	// 순위 산정 근거인 ZSET score와 같은 출처만 노출한다는 RANK-002 원칙(PR #234 리뷰 차단)이 유실 상태에도
	// 그대로 적용되기 때문이다. status가 REBUILDING이라 이 0은 "손익이 0"이 아니라 "아직 신뢰할 수 없음"으로
	// 읽어야 한다는 것이 계약이다.
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
