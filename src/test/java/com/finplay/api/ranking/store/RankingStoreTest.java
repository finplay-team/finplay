// mock Redis(StringRedisTemplate)로 RankingStore.addScoreWithRetry의 재시도·예외 억제를 검증하는 단위 테스트다.
package com.finplay.api.ranking.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Market;
import com.finplay.api.ranking.dto.RankingEntryDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class RankingStoreTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

	private RankingStore rankingStore() {
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		return new RankingStore(redisTemplate);
	}

	@Test
	void addScoreWithRetrySwallowsExceptionAfterExhaustingThreeAttempts() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down"))
			.when(zSetOperations)
			.add(eq("ranking:STOCK"), eq("1"), anyDouble());

		assertThatCode(() -> rankingStore.addScoreWithRetry(Market.STOCK, 1L, 1000L))
			.doesNotThrowAnyException();

		verify(zSetOperations, times(3)).add(eq("ranking:STOCK"), eq("1"), anyDouble());
	}

	@Test
	void addScoreWithRetryCallsZaddOnlyOnceOnSuccess() {
		RankingStore rankingStore = rankingStore();

		rankingStore.addScoreWithRetry(Market.CRYPTO, 2L, 500L);

		verify(zSetOperations, times(1)).add("ranking:CRYPTO", "2", 500.0);
		verify(zSetOperations, times(1)).add(any(), any(), anyDouble());
	}

	// PR #196 리뷰 지적(차단 2): limit 경계에 동점 그룹이 걸쳐 있을 때 그 score의 전체 멤버를 다시 조회하기 위한
	// findAllAtScore가 ZRANGEBYSCORE score score와 동치인 정확한 구간 조회를 수행하는지 확인한다.
	@Test
	void findAllAtScoreReturnsAllMembersWithExactScore() {
		RankingStore rankingStore = rankingStore();
		Set<String> members = new LinkedHashSet<>(List.of("1", "2"));
		when(zSetOperations.rangeByScore("ranking:STOCK", 100.0, 100.0, 0, 500)).thenReturn(members);

		List<RankingEntryDto> entries = rankingStore.findAllAtScore(Market.STOCK, 100L);

		assertThat(entries).containsExactlyInAnyOrder(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(2L, 100L));
	}

	@Test
	void findAllAtScoreReturnsEmptyListWhenNoMemberMatches() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.rangeByScore("ranking:CRYPTO", 100.0, 100.0, 0, 500)).thenReturn(Set.of());

		List<RankingEntryDto> entries = rankingStore.findAllAtScore(Market.CRYPTO, 100L);

		assertThat(entries).isEmpty();
	}

	// 이슈 #270: 경계 동점 그룹이 상한(500)을 넘으면 Redis LIMIT 옵션으로 그 이상을 애초에 가져오지 않는다 —
	// 반환된 멤버 수가 상한과 같으면(잘렸을 가능성) 결과는 그대로 반환하되 절단 여부를 로그로 남긴다.
	@Test
	void findAllAtScoreTruncatesToCapWithoutThrowingWhenTiedGroupIsHuge() {
		RankingStore rankingStore = rankingStore();
		Set<String> members = new LinkedHashSet<>();
		for (int i = 1; i <= 500; i++) {
			members.add(String.valueOf(i));
		}
		when(zSetOperations.rangeByScore("ranking:STOCK", 0.0, 0.0, 0, 500)).thenReturn(members);

		List<RankingEntryDto> entries = rankingStore.findAllAtScore(Market.STOCK, 0L);

		assertThat(entries).hasSize(500);
		verify(zSetOperations, times(1)).rangeByScore("ranking:STOCK", 0.0, 0.0, 0, 500);
	}

	@Test
	void countStrictlyGreaterClampsLowerBoundWhenScoreIsLongMaxValueToAvoidOverflow() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.count("ranking:STOCK", (double)Long.MAX_VALUE, Double.POSITIVE_INFINITY))
			.thenReturn(0L);

		long count = rankingStore.countStrictlyGreater(Market.STOCK, Long.MAX_VALUE);

		assertThat(count).isZero();
		verify(zSetOperations).count("ranking:STOCK", (double)Long.MAX_VALUE, Double.POSITIVE_INFINITY);
	}

	// RANK-002: 계좌 하나의 score를 ZSCORE로 단건 조회한다 — member가 있으면 정수 score를 반환한다.
	@Test
	void scoreReturnsRoundedScoreWhenMemberExists() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.score("ranking:STOCK", "1")).thenReturn(5_000.0);

		Long score = rankingStore.score(Market.STOCK, 1L);

		assertThat(score).isEqualTo(5_000L);
	}

	// RANK-002: member가 ZSET에 없으면(매도 이력 없음) null을 반환한다 — RankingService.getMyRanking이 이
	// null 여부로 매도 이력 유무를 판정한다.
	@Test
	void scoreReturnsNullWhenMemberDoesNotExist() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.score("ranking:CRYPTO", "999")).thenReturn(null);

		Long score = rankingStore.score(Market.CRYPTO, 999L);

		assertThat(score).isNull();
	}

	// 이슈 #279 재구성: 임시 키 DEL → ZADD → RENAME 순서를 지켜야 한다. DEL이 빠지면 이전 실행이 남긴
	// 잔재와 이번 결과가 합쳐지고, RENAME이 마지막이 아니면 조회가 부분 적재 상태를 본다.
	@Test
	void replaceAllDeletesRebuildKeyThenAddsThenRenamesInOrder() {
		RankingStore rankingStore = rankingStore();

		rankingStore.replaceAll(Market.STOCK, List.of(new RankingEntryDto(1L, 3_000L), new RankingEntryDto(2L, -500L)));

		InOrder inOrder = inOrder(redisTemplate, zSetOperations);
		inOrder.verify(redisTemplate).delete("ranking:STOCK:rebuild");
		inOrder.verify(zSetOperations).add(eq("ranking:STOCK:rebuild"), anySet());
		inOrder.verify(redisTemplate).rename("ranking:STOCK:rebuild", "ranking:STOCK");
		verify(redisTemplate, never()).delete("ranking:STOCK");
	}

	// 적재 내용 계약: member는 accountId 문자열, score는 realized_pnl(음수 포함)이다.
	@Test
	void replaceAllLoadsEveryEntryAsMemberAndScoreTuple() {
		RankingStore rankingStore = rankingStore();

		rankingStore.replaceAll(Market.CRYPTO, List.of(new RankingEntryDto(7L, 0L), new RankingEntryDto(8L, -1_200L)));

		ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor = ArgumentCaptor.captor();
		verify(zSetOperations).add(eq("ranking:CRYPTO:rebuild"), captor.capture());
		assertThat(captor.getValue())
			.extracting(ZSetOperations.TypedTuple::getValue, ZSetOperations.TypedTuple::getScore)
			.containsExactlyInAnyOrder(tuple("7", 0.0), tuple("8", -1_200.0));
	}

	// 경계: 대상 0건이면 임시 키가 만들어지지 않아 RENAME이 ERR no such key로 실패한다 —
	// RENAME 대신 본 키를 DEL해 빈 랭킹으로 만든다(원장에서 사라진 계좌가 ZSET에 남지 않게 한다).
	@Test
	void replaceAllDeletesMainKeyInsteadOfRenamingWhenEntriesAreEmpty() {
		RankingStore rankingStore = rankingStore();

		rankingStore.replaceAll(Market.STOCK, List.of());

		InOrder inOrder = inOrder(redisTemplate);
		inOrder.verify(redisTemplate).delete("ranking:STOCK:rebuild");
		inOrder.verify(redisTemplate).delete("ranking:STOCK");
		verify(redisTemplate, never()).rename(any(), any());
		verify(zSetOperations, never()).add(any(), anySet());
	}

	// 청크(500)로 나눠 적재해도 임시 키에 누적되므로 RENAME은 마지막에 딱 1회여야 한다.
	@Test
	void replaceAllSplitsIntoChunksButRenamesExactlyOnce() {
		RankingStore rankingStore = rankingStore();
		List<RankingEntryDto> entries = new ArrayList<>();
		for (int i = 1; i <= 501; i++) {
			entries.add(new RankingEntryDto((long)i, i));
		}

		rankingStore.replaceAll(Market.STOCK, entries);

		verify(zSetOperations, times(2)).add(eq("ranking:STOCK:rebuild"), anySet());
		verify(redisTemplate, times(1)).rename("ranking:STOCK:rebuild", "ranking:STOCK");
	}

	// 재구성 실패는 밖으로 전파하지 않는다 — 호출자가 기동 훅과 스케줄러라 예외가 새면 기동이 실패하거나
	// 스케줄러 스레드가 죽는다(addScoreWithRetry와 같은 방침). 실패 시 임시 키 정리를 한 번 시도한다.
	@Test
	void replaceAllSwallowsExceptionAndCleansUpRebuildKeyWhenRenameFails() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down"))
			.when(redisTemplate)
			.rename("ranking:STOCK:rebuild", "ranking:STOCK");

		assertThatCode(() -> rankingStore.replaceAll(Market.STOCK, List.of(new RankingEntryDto(1L, 10L))))
			.doesNotThrowAnyException();

		verify(redisTemplate, times(2)).delete("ranking:STOCK:rebuild");
	}

	// 첫 DEL(임시 키 잔재 제거)에서 터지는 경우. catch가 정리로 delete를 한 번 더 부르는데 그것도 같은 이유로
	// 터진다 — cleanUpRebuildKey 안쪽 catch가 그 두 번째 실패까지 삼켜야 예외가 기동 훅으로 새지 않는다.
	// 이 경로가 열려 있으면 Redis가 죽은 채 기동할 때 ApplicationReadyEvent 훅이 통째로 실패한다.
	@Test
	void replaceAllSwallowsExceptionWhenInitialDeleteAndCleanUpBothFail() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("ranking:STOCK:rebuild");

		assertThatCode(() -> rankingStore.replaceAll(Market.STOCK, List.of(new RankingEntryDto(1L, 10L))))
			.doesNotThrowAnyException();

		verify(zSetOperations, never()).add(any(), anySet());
		verify(redisTemplate, never()).rename(any(), any());
	}

	// 0건 경계에서 본 키 DEL이 실패해도 마찬가지다 — 재구성 대상이 사라진 시장에서 Redis가 흔들려도
	// 스케줄러 스레드가 죽지 않아야 한다(다음 배치에 다시 돈다).
	@Test
	void replaceAllSwallowsExceptionWhenMainKeyDeleteFailsOnEmptyEntries() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("ranking:CRYPTO");

		assertThatCode(() -> rankingStore.replaceAll(Market.CRYPTO, List.of()))
			.doesNotThrowAnyException();

		verify(redisTemplate, never()).rename(any(), any());
	}

	// ZADD 단계에서 터져도 마찬가지로 전파하지 않고 RENAME도 시도하지 않는다(부분 적재를 노출하지 않는다).
	@Test
	void replaceAllSwallowsExceptionAndSkipsRenameWhenZaddFails() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down"))
			.when(zSetOperations)
			.add(eq("ranking:CRYPTO:rebuild"), anySet());

		assertThatCode(() -> rankingStore.replaceAll(Market.CRYPTO, List.of(new RankingEntryDto(1L, 10L))))
			.doesNotThrowAnyException();

		verify(redisTemplate, never()).rename(any(), any());
	}

	// 아래 4건은 반환값 계약이다(PR #284 QA 지적). 예외를 삼키는 것과 성공/실패를 감추는 것은 다르다 —
	// 호출자(RankingRebuildService)는 이 boolean으로만 성공을 판정해 "완료" 로그를 남길지 정한다.
	// 반환값이 항상 true가 되는 회귀가 생기면 Redis가 죽은 tick에서도 완료 로그가 찍힌다.
	@Test
	void replaceAllReturnsTrueWhenRenameSucceeds() {
		RankingStore rankingStore = rankingStore();

		boolean replaced = rankingStore.replaceAll(Market.STOCK, List.of(new RankingEntryDto(1L, 10L)));

		assertThat(replaced).isTrue();
	}

	// 0건 경계도 성공이다 — 본 키를 지워 빈 랭킹으로 만드는 것이 의도한 교체 결과다(실패가 아니다).
	@Test
	void replaceAllReturnsTrueWhenEntriesAreEmptyBecauseDeletingTheMainKeyIsTheIntendedResult() {
		RankingStore rankingStore = rankingStore();

		boolean replaced = rankingStore.replaceAll(Market.STOCK, List.of());

		assertThat(replaced).isTrue();
	}

	@Test
	void replaceAllReturnsFalseWhenRenameFails() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down"))
			.when(redisTemplate)
			.rename("ranking:STOCK:rebuild", "ranking:STOCK");

		boolean replaced = rankingStore.replaceAll(Market.STOCK, List.of(new RankingEntryDto(1L, 10L)));

		assertThat(replaced).isFalse();
	}

	// 0건 경계의 실패도 false다 — 여기서 true를 돌려주면 본 키가 그대로 남은 채 "완료"로 읽힌다.
	@Test
	void replaceAllReturnsFalseWhenMainKeyDeleteFailsOnEmptyEntries() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("ranking:CRYPTO");

		boolean replaced = rankingStore.replaceAll(Market.CRYPTO, List.of());

		assertThat(replaced).isFalse();
	}
}
