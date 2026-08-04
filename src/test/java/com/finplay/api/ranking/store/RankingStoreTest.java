// mock Redis(StringRedisTemplate)로 RankingStore.addScoreWithRetry의 재시도·예외 억제를 검증하는 단위 테스트다.
package com.finplay.api.ranking.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Market;
import com.finplay.api.ranking.dto.RankingEntryDto;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
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
		when(zSetOperations.rangeByScore("ranking:STOCK", 100.0, 100.0)).thenReturn(members);

		List<RankingEntryDto> entries = rankingStore.findAllAtScore(Market.STOCK, 100L);

		assertThat(entries).containsExactlyInAnyOrder(
			new RankingEntryDto(1L, 100L),
			new RankingEntryDto(2L, 100L));
	}

	@Test
	void findAllAtScoreReturnsEmptyListWhenNoMemberMatches() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.rangeByScore("ranking:CRYPTO", 100.0, 100.0)).thenReturn(Set.of());

		List<RankingEntryDto> entries = rankingStore.findAllAtScore(Market.CRYPTO, 100L);

		assertThat(entries).isEmpty();
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
}
