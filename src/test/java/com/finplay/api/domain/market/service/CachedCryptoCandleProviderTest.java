package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.store.CryptoCandleStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CachedCryptoCandleProviderTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 15, 40, 0);

	@Mock
	private BithumbRestCandleProvider delegate;

	@Mock
	private CryptoCandleStore candleStore;

	private CachedCryptoCandleProvider provider;

	@BeforeEach
	void setUp() {
		Clock fixedClock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
		provider = new CachedCryptoCandleProvider(delegate, candleStore, fixedClock);
	}

	private CryptoCandleDto candleAt(LocalDateTime sourceTime, String price) {
		BigDecimal p = new BigDecimal(price);
		return new CryptoCandleDto(sourceTime, p, p, p, p, BigDecimal.ONE);
	}

	private List<CryptoCandleDto> candlesEveryMinute(LocalDateTime start, LocalDateTime endInclusive, String price) {
		List<CryptoCandleDto> result = new ArrayList<>();
		LocalDateTime t = start;
		while (!t.isAfter(endInclusive)) {
			result.add(candleAt(t, price));
			t = t.plusMinutes(1);
		}
		return result;
	}

	@Test
	@DisplayName("interval이 1m이 아니면 즉시 위임하고 캐시를 조회하지 않는다")
	void nonOneMinuteIntervalDelegatesImmediatelyWithoutTouchingCache() {
		LocalDateTime from = NOW.minusDays(1);
		when(delegate.getCandles("BTC", CandleInterval.ONE_DAY, from, NOW)).thenReturn(List.of());

		provider.getCandles("BTC", CandleInterval.ONE_DAY, from, NOW);

		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_DAY, from, NOW);
		verify(candleStore, never()).getSince(any());
		verify(candleStore, never()).getCandles(any(), any(), any());
	}

	@Test
	@DisplayName("from > to면 원본 값 그대로 위임한다 (이 클래스의 관심사가 아님)")
	void fromAfterToDelegatesRawValuesUnchanged() {
		LocalDateTime from = NOW;
		LocalDateTime to = NOW.minusMinutes(10);
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(List.of());

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
		verify(candleStore, never()).getSince(any());
	}

	@Test
	@DisplayName("since가 없으면(캐시 신뢰 구간 자체가 없음) 전량 빗썸에 위임한다")
	void noSinceDelegatesEntireRange() {
		LocalDateTime from = NOW.minusMinutes(10);
		when(candleStore.getSince("BTC")).thenReturn(Optional.empty());
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW)).thenReturn(List.of());

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);
		verify(candleStore, never()).getCandles(any(), any(), any());
	}

	@Test
	@DisplayName("전체 구간이 since 이후(캐시 구간)면 빗썸을 호출하지 않는다")
	void entireRangeAfterSinceNeverCallsDelegateForCandles() {
		LocalDateTime from = NOW.minusMinutes(5);
		LocalDateTime since = NOW.minusMinutes(20);
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, NOW)).thenReturn(List.of(candleAt(from, "100")));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).hasSize(1);
		verify(delegate, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	@DisplayName("since가 요청 범위 안이면 since 이전은 빗썸, since 이후는 캐시로 나뉘어 합쳐진다")
	void splitsRangeAtSinceAndMergesBothParts() {
		LocalDateTime from = NOW.minusMinutes(10);
		LocalDateTime since = NOW.minusMinutes(3);
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, since.minusMinutes(1)))
			.thenReturn(List.of(candleAt(from, "100"), candleAt(since.minusMinutes(1), "101")));
		when(candleStore.getCandles("BTC", since, NOW))
			.thenReturn(List.of(candleAt(since, "200"), candleAt(NOW, "201")));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactly(from, since.minusMinutes(1), since, NOW);
	}

	@Test
	@DisplayName("요청 구간 전체가 since보다 과거면 위임 to를 요청 to로 클램프하고 캐시는 건너뛴다")
	void rangeEntirelyBeforeSinceClampsDelegateToRequestedTo() {
		LocalDateTime from = NOW.minusMinutes(60);
		LocalDateTime to = NOW.minusMinutes(50);
		LocalDateTime since = NOW.minusMinutes(10);
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to))
			.thenReturn(List.of(candleAt(from, "100"), candleAt(to, "101")));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(result).extracting(CryptoCandleDto::sourceTime).containsExactly(from, to);
		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
		verify(candleStore, never()).getCandles(any(), any(), any());
	}

	@Test
	@DisplayName("빗썸 구간과 캐시 구간의 sourceTime이 겹치면 캐시 쪽 값을 채택한다")
	void overlappingSourceTimePrefersCachedValue() {
		LocalDateTime from = NOW.minusMinutes(10);
		LocalDateTime since = NOW.minusMinutes(3);
		LocalDateTime overlapMinute = since;
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, since.minusMinutes(1)))
			.thenReturn(List.of(candleAt(overlapMinute, "111")));
		when(candleStore.getCandles("BTC", since, NOW)).thenReturn(List.of(candleAt(overlapMinute, "999")));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).extracting(CryptoCandleDto::close).containsExactly(new BigDecimal("999"));
	}

	@Test
	@DisplayName("since 조회 자체가 실패하면 Redis 단일 장애점이 되지 않고 전량 빗썸에 위임한다")
	void redisFailureOnGetSinceFallsBackToDelegateEntirely() {
		LocalDateTime from = NOW.minusMinutes(10);
		when(candleStore.getSince("BTC")).thenThrow(new RuntimeException("Redis down"));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW)).thenReturn(List.of());

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);
	}

	@Test
	@DisplayName("캐시 구간 조회(getCandles)가 실패하면 그 구간만 빗썸으로 넘어간다")
	void redisFailureOnGetCandlesFallsBackToDelegateForThatRange() {
		LocalDateTime from = NOW.minusMinutes(5);
		LocalDateTime since = NOW.minusMinutes(20);
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, NOW)).thenThrow(new RuntimeException("Redis down"));
		when(delegate.getCandles(eq("BTC"), eq(CandleInterval.ONE_MINUTE), eq(from), eq(NOW))).thenReturn(
			List.of(candleAt(from, "100")));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).hasSize(1);
	}

	@Test
	@DisplayName("from·to가 둘 다 null이면 현재 시각 기준 최근 200분으로 정규화한다")
	void nullFromAndToNormalizesToLatest200Minutes() {
		when(candleStore.getSince("BTC")).thenReturn(Optional.empty());

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);

		verify(delegate, times(1))
			.getCandles("BTC", CandleInterval.ONE_MINUTE, NOW.minusMinutes(199), NOW);
	}

	@Test
	@DisplayName("요청 범위가 200분을 넘으면 to 기준 최신 200분으로 캡한다")
	void rangeWiderThan200MinutesIsCappedToLatest200() {
		LocalDateTime from = NOW.minusMinutes(500);
		when(candleStore.getSince("BTC")).thenReturn(Optional.empty());

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		verify(delegate, times(1))
			.getCandles("BTC", CandleInterval.ONE_MINUTE, NOW.minusMinutes(199), NOW);
	}


	@Test
	@DisplayName("커서 위치 1 (C ≤ S): 전량 빗썸 위임, 캐시 조회 자체를 건너뛴다")
	void cursorPositionCLessThanOrEqualSinceDelegatesEntireRangeAndSkipsCache() {
		LocalDateTime from = NOW.minusMinutes(20);
		LocalDateTime to = NOW.minusMinutes(15);
		LocalDateTime since = NOW.minusMinutes(10);
		List<CryptoCandleDto> delegated = candlesEveryMinute(from, to, "1");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(delegated);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactlyElementsOf(delegated.stream().map(CryptoCandleDto::sourceTime).toList());
		verify(candleStore, never()).getCandles(any(), any(), any());
	}

	@Test
	@DisplayName("커서 위치 2 (C == S): 전량 위임이며 배타 상한이 워터마크 시각 봉을 정확히 걸러낸다")
	void cursorPositionCEqualsSinceExcludesWatermarkCandleByExclusiveBound() {
		LocalDateTime since = NOW.minusMinutes(3);
		LocalDateTime to = since.minusMinutes(1);
		LocalDateTime from = NOW.minusMinutes(8);
		List<CryptoCandleDto> delegated = candlesEveryMinute(from, to, "1");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(delegated);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactlyElementsOf(delegated.stream().map(CryptoCandleDto::sourceTime).toList())
			.doesNotContain(since);
		verify(candleStore, never()).getCandles(any(), any(), any());
	}

	@Test
	@DisplayName("커서 위치 3 (C > S, effectiveFrom < S): 위임 구간과 캐시 구간이 1분 간격으로 맞닿고 겹치지 않는다")
	void cursorPositionStraddlingBoundaryAdjoinsDelegatedAndCachedRangesWithoutGapOrOverlap() {
		LocalDateTime from = NOW.minusMinutes(10);
		LocalDateTime since = NOW.minusMinutes(4);
		LocalDateTime to = NOW;
		List<CryptoCandleDto> delegated = candlesEveryMinute(from, since.minusMinutes(1), "1");
		List<CryptoCandleDto> cached = candlesEveryMinute(since, to, "2");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, since.minusMinutes(1)))
			.thenReturn(delegated);
		when(candleStore.getCandles("BTC", since, to)).thenReturn(cached);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		List<LocalDateTime> expectedTimes = candlesEveryMinute(from, to, "0").stream().map(CryptoCandleDto::sourceTime)
			.toList();
		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactlyElementsOf(expectedTimes)
			.doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("커서 위치 4 (effectiveFrom ≥ S): 전량 캐시 — D-2가 걸리는 주 무대")
	void cursorPositionAllCacheWhenEffectiveFromAtOrAfterSince() {
		LocalDateTime from = NOW.minusMinutes(5);
		LocalDateTime since = NOW.minusMinutes(20);
		List<CryptoCandleDto> cached = candlesEveryMinute(from, NOW, "3");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, NOW)).thenReturn(cached);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactlyElementsOf(cached.stream().map(CryptoCandleDto::sourceTime).toList());
		verify(delegate, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	@DisplayName("경계를 걸친 요청에서 캐시 구간 조회만 Redis 장애로 실패해도 그 구간만 위임되어 경계가 어긋나지 않는다")
	void redisFailureAtStraddlingBoundaryFallsBackOnlyForCacheSubrangeKeepingBoundaryCorrect() {
		LocalDateTime from = NOW.minusMinutes(10);
		LocalDateTime since = NOW.minusMinutes(4);
		LocalDateTime to = NOW;
		List<CryptoCandleDto> delegatedPart = candlesEveryMinute(from, since.minusMinutes(1), "1");
		List<CryptoCandleDto> fallbackPart = candlesEveryMinute(since, to, "2");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, since.minusMinutes(1)))
			.thenReturn(delegatedPart);
		when(candleStore.getCandles("BTC", since, to)).thenThrow(new RuntimeException("Redis down"));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, since, to)).thenReturn(fallbackPart);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		List<LocalDateTime> expectedTimes = candlesEveryMinute(from, to, "0").stream().map(CryptoCandleDto::sourceTime)
			.toList();
		assertThat(result).extracting(CryptoCandleDto::sourceTime).containsExactlyElementsOf(expectedTimes);
		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, since.minusMinutes(1));
		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, since, to);
	}


	@Test
	@DisplayName("D-2 ⓐ: 캐시가 성긴 200분 창 → 보충 위임이 1회 호출되고 최종 결과가 200개다")
	void d2SupplementFillsSparseCacheToReachTwoHundred() {
		LocalDateTime to = NOW;
		LocalDateTime from = to.minusMinutes(199);
		LocalDateTime since = NOW.minusDays(2);
		List<CryptoCandleDto> allMinutes = candlesEveryMinute(from, to, "1");
		List<CryptoCandleDto> sparseCached = new ArrayList<>();
		for (int i = 0; i < allMinutes.size(); i++) {
			if (i % 4 != 0) {
				sparseCached.add(allMinutes.get(i));
			}
		}
		List<CryptoCandleDto> supplement = candlesEveryMinute(from, to, "9");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, to)).thenReturn(sparseCached);
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(supplement);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(sparseCached).hasSize(150);
		assertThat(result).hasSize(200);
		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
		LocalDateTime cachedMinute = sparseCached.get(0).sourceTime();
		assertThat(result.stream().filter(c -> c.sourceTime().equals(cachedMinute)).findFirst().orElseThrow().close())
			.isEqualByComparingTo("1");
	}

	@Test
	@DisplayName("D-2 ⓑ: 캐시가 이미 200개를 채운 창 → 보충 위임이 호출되지 않는다")
	void d2SupplementNotCalledWhenCacheAlreadyFillsTwoHundred() {
		LocalDateTime to = NOW;
		LocalDateTime from = to.minusMinutes(199);
		LocalDateTime since = NOW.minusDays(2);
		List<CryptoCandleDto> fullCached = candlesEveryMinute(from, to, "5");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, to)).thenReturn(fullCached);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(fullCached).hasSize(200);
		assertThat(result).hasSize(200);
		verify(delegate, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	@DisplayName("D-2 ⓒ: 보충으로 200개를 채웠는데 캐시 전용 진행 중 봉이 더 있어 초과하면 최신 200개로 잘리고, "
		+ "겹치는 시각은 캐시 값이 이긴다")
	void d2OverflowFromSupplementPlusCacheOnlyLiveCandleIsTrimmedToLatestTwoHundredKeepingCachePriority() {
		LocalDateTime to = NOW;
		LocalDateTime from = to.minusMinutes(199);
		LocalDateTime since = NOW.minusDays(2);
		LocalDateTime overlapMinute = from.plusMinutes(50);
		List<CryptoCandleDto> sparseCached = List.of(
			candleAt(overlapMinute, "999"),
			candleAt(to, "777"));
		List<CryptoCandleDto> supplement = candlesEveryMinute(from.minusMinutes(1), to.minusMinutes(1), "1");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, to)).thenReturn(sparseCached);
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(supplement);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(supplement).hasSize(200);
		assertThat(result).hasSize(200);
		assertThat(result.get(0).sourceTime()).isEqualTo(from);
		assertThat(result.get(result.size() - 1).sourceTime()).isEqualTo(to);
		assertThat(result.get(result.size() - 1).close()).isEqualByComparingTo("777");
		assertThat(result.stream()
			.filter(c -> c.sourceTime().equals(overlapMinute))
			.findFirst().orElseThrow().close())
			.isEqualByComparingTo("999");
		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
	}
}
