package com.finplay.api.domain.ranking.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
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

	@Test
	void scoreReturnsRoundedScoreWhenMemberExists() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.score("ranking:STOCK", "1")).thenReturn(5_000.0);

		Long score = rankingStore.score(Market.STOCK, 1L);

		assertThat(score).isEqualTo(5_000L);
	}

	@Test
	void scoreReturnsNullWhenMemberDoesNotExist() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.score("ranking:CRYPTO", "999")).thenReturn(null);

		Long score = rankingStore.score(Market.CRYPTO, 999L);

		assertThat(score).isNull();
	}


	@Test
	void topNThrowsRankingStoreUnavailableWhenRedisConnectionFails() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.reverseRangeWithScores("ranking:STOCK", 0, 10))
			.thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

		assertThatThrownBy(() -> rankingStore.topN(Market.STOCK, 11))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.RANKING_STORE_UNAVAILABLE)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void findAllAtScoreThrowsRankingStoreUnavailableWhenRedisConnectionFails() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.rangeByScore("ranking:STOCK", 100.0, 100.0, 0, 500))
			.thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

		assertThatThrownBy(() -> rankingStore.findAllAtScore(Market.STOCK, 100L))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.RANKING_STORE_UNAVAILABLE)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void countStrictlyGreaterThrowsRankingStoreUnavailableWhenRedisConnectionFails() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.count("ranking:STOCK", 101.0, Double.POSITIVE_INFINITY))
			.thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

		assertThatThrownBy(() -> rankingStore.countStrictlyGreater(Market.STOCK, 100L))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.RANKING_STORE_UNAVAILABLE)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void scoreThrowsRankingStoreUnavailableWhenRedisConnectionFails() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.score("ranking:STOCK", "1"))
			.thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

		assertThatThrownBy(() -> rankingStore.score(Market.STOCK, 1L))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.RANKING_STORE_UNAVAILABLE)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void topNThrowsRankingStoreUnavailableWhenRedisTimesOut() {
		RankingStore rankingStore = rankingStore();
		when(zSetOperations.reverseRangeWithScores("ranking:STOCK", 0, 10))
			.thenThrow(new QueryTimeoutException("Redis command timed out"));

		assertThatThrownBy(() -> rankingStore.topN(Market.STOCK, 11))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.RANKING_STORE_UNAVAILABLE)
			.hasCauseInstanceOf(QueryTimeoutException.class);
	}

	@Test
	void topNDoesNotWrapNonConnectionRedisSystemExceptionAndLetsItPropagate() {
		RankingStore rankingStore = rankingStore();
		RedisSystemException wrongType = new RedisSystemException("WRONGTYPE", new RuntimeException("WRONGTYPE"));
		when(zSetOperations.reverseRangeWithScores("ranking:STOCK", 0, 10)).thenThrow(wrongType);

		assertThatThrownBy(() -> rankingStore.topN(Market.STOCK, 11))
			.isSameAs(wrongType)
			.isNotInstanceOf(BusinessException.class);
	}

	@Test
	void addScoreWithRetryStillSwallowsRedisConnectionFailureUnlikeReadMethods() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
			.when(zSetOperations)
			.add(eq("ranking:STOCK"), eq("1"), anyDouble());

		assertThatCode(() -> rankingStore.addScoreWithRetry(Market.STOCK, 1L, 1000L))
			.doesNotThrowAnyException();
	}

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

	@Test
	void replaceAllSwallowsExceptionWhenInitialDeleteAndCleanUpBothFail() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("ranking:STOCK:rebuild");

		assertThatCode(() -> rankingStore.replaceAll(Market.STOCK, List.of(new RankingEntryDto(1L, 10L))))
			.doesNotThrowAnyException();

		verify(zSetOperations, never()).add(any(), anySet());
		verify(redisTemplate, never()).rename(any(), any());
	}

	@Test
	void replaceAllSwallowsExceptionWhenMainKeyDeleteFailsOnEmptyEntries() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("ranking:CRYPTO");

		assertThatCode(() -> rankingStore.replaceAll(Market.CRYPTO, List.of()))
			.doesNotThrowAnyException();

		verify(redisTemplate, never()).rename(any(), any());
	}

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

	@Test
	void replaceAllReturnsTrueWhenRenameSucceeds() {
		RankingStore rankingStore = rankingStore();

		boolean replaced = rankingStore.replaceAll(Market.STOCK, List.of(new RankingEntryDto(1L, 10L)));

		assertThat(replaced).isTrue();
	}

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

	@Test
	void replaceAllReturnsFalseWhenMainKeyDeleteFailsOnEmptyEntries() {
		RankingStore rankingStore = rankingStore();
		doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("ranking:CRYPTO");

		boolean replaced = rankingStore.replaceAll(Market.CRYPTO, List.of());

		assertThat(replaced).isFalse();
	}
}
