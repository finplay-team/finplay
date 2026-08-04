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
