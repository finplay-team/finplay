// 목 BithumbRestCandleProvider·CryptoCandleStore로 CachedCryptoCandleProvider의 구간 분할·캐시 우선·장애 폴백을 검증하는 단위 테스트
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

	// [start, endInclusive] 구간의 매 분마다 봉 하나씩 생성한다 — 중복·누락 없는 "이상적인" 기준 시각 집합을 만드는 데 쓴다.
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
		LocalDateTime since = NOW.minusMinutes(20); // since가 요청 범위보다 과거 → 전체가 캐시 구간
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
			.containsExactly(from, since.minusMinutes(1), since, NOW); // 시각 오름차순
	}

	@Test
	@DisplayName("요청 구간 전체가 since보다 과거면 위임 to를 요청 to로 클램프하고 캐시는 건너뛴다")
	void rangeEntirelyBeforeSinceClampsDelegateToRequestedTo() {
		// 매도 회고는 언제나 체결보다 과거를 조회하므로 WS 재연결 직후(since가 최근)에 상시로 이 조건이 된다.
		// 클램프가 없으면 to가 since-1분이 되고 빗썸이 그 시각 기준 최근 200봉을 돌려줘 요청 구간과 겹치지
		// 않는 봉만 오고, 호출부의 [from, to] 필터가 전부 걸러 결과가 빈다 (PR #281 리뷰).
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
		// 정상적인 분할이면 delegate 구간([from, since-1])과 cache 구간([since, to])은 항상 서로소다. 그래도
		// 병합 로직의 방어 규칙(겹치면 캐시 우선)이 실제로 동작하는지 확인하기 위해, delegate가 요청 범위
		// 밖 시각을 반환하는(비정상) 상황을 가정해 두 결과가 같은 sourceTime을 갖게 만든다.
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

	// ===== 커서 위치 4종 (plan.md §9-3 표, since 워터마크 S·커서 C) =====
	// 이 클래스는 커서를 모른다 — 호출부(CandleQueryService)가 이미 C를 effectiveTo(=C-1분)로 정규화해 넘긴다.
	// 그래서 아래 각 테스트는 "커서가 그 위치에 있었다면" 넘어왔을 from·to·since 조합을 직접 구성한다.

	@Test
	@DisplayName("커서 위치 1 (C ≤ S): 전량 빗썸 위임, 캐시 조회 자체를 건너뛴다")
	void cursorPositionCLessThanOrEqualSinceDelegatesEntireRangeAndSkipsCache() {
		LocalDateTime from = NOW.minusMinutes(20);
		LocalDateTime to = NOW.minusMinutes(15); // effectiveTo = C-1분
		LocalDateTime since = NOW.minusMinutes(10); // since > to → C ≤ S
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
		LocalDateTime to = since.minusMinutes(1); // effectiveTo = C-1분 = since-1분 → C == S
		LocalDateTime from = NOW.minusMinutes(8);
		List<CryptoCandleDto> delegated = candlesEveryMinute(from, to, "1");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(delegated);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactlyElementsOf(delegated.stream().map(CryptoCandleDto::sourceTime).toList())
			.doesNotContain(since); // since(=S) 시각 봉은 직전 페이지에서 이미 나갔어야 하고 여기 다시 나오면 안 된다
		verify(candleStore, never()).getCandles(any(), any(), any());
	}

	@Test
	@DisplayName("커서 위치 3 (C > S, effectiveFrom < S): 위임 구간과 캐시 구간이 1분 간격으로 맞닿고 겹치지 않는다")
	void cursorPositionStraddlingBoundaryAdjoinsDelegatedAndCachedRangesWithoutGapOrOverlap() {
		LocalDateTime from = NOW.minusMinutes(10);
		LocalDateTime since = NOW.minusMinutes(4);
		LocalDateTime to = NOW;
		List<CryptoCandleDto> delegated = candlesEveryMinute(from, since.minusMinutes(1), "1"); // [-10, -5]
		List<CryptoCandleDto> cached = candlesEveryMinute(since, to, "2"); // [-4, 0]
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, since.minusMinutes(1)))
			.thenReturn(delegated);
		when(candleStore.getCandles("BTC", since, to)).thenReturn(cached);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		List<LocalDateTime> expectedTimes = candlesEveryMinute(from, to, "0").stream().map(CryptoCandleDto::sourceTime)
			.toList(); // 연속 11분
		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactlyElementsOf(expectedTimes) // 중복 0건·누락 0건 — 정확히 이어붙는다
			.doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("커서 위치 4 (effectiveFrom ≥ S): 전량 캐시 — D-2가 걸리는 주 무대")
	void cursorPositionAllCacheWhenEffectiveFromAtOrAfterSince() {
		LocalDateTime from = NOW.minusMinutes(5);
		LocalDateTime since = NOW.minusMinutes(20); // since가 요청 범위보다 과거 → 전체가 캐시 구간
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
		List<CryptoCandleDto> delegatedPart = candlesEveryMinute(from, since.minusMinutes(1), "1"); // [-10, -5]
		List<CryptoCandleDto> fallbackPart = candlesEveryMinute(since, to, "2"); // [-4, 0]
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

	// ===== D-2: 캐시 구간이 200분 창을 못 채우면 빗썸에 한 번 더 위임해 보충한다 (plan.md §9-2) =====

	@Test
	@DisplayName("D-2 ⓐ: 캐시가 성긴 200분 창 → 보충 위임이 1회 호출되고 최종 결과가 200개다")
	void d2SupplementFillsSparseCacheToReachTwoHundred() {
		LocalDateTime to = NOW;
		LocalDateTime from = to.minusMinutes(199); // 정확히 200분 폭
		LocalDateTime since = NOW.minusDays(2); // since가 훨씬 과거 → 전부 캐시 구간
		List<CryptoCandleDto> allMinutes = candlesEveryMinute(from, to, "1");
		// 4개 중 1개 꼴로 비워 200개 슬롯 중 150개만 채워진 성긴 캐시를 만든다 (200 - 50 = 150)
		List<CryptoCandleDto> sparseCached = new ArrayList<>();
		for (int i = 0; i < allMinutes.size(); i++) {
			if (i % 4 != 0) {
				sparseCached.add(allMinutes.get(i));
			}
		}
		List<CryptoCandleDto> supplement = candlesEveryMinute(from, to, "9"); // 위임은 §9-1대로 200개를 채워 온다
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, to)).thenReturn(sparseCached);
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(supplement);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(sparseCached).hasSize(150);
		assertThat(result).hasSize(200);
		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
		// 캐시가 이미 채워둔 시각은 보충 위임 값에 덮이지 않고 캐시 값이 그대로 남는다 (027 우선순위 유지)
		LocalDateTime cachedMinute = sparseCached.get(0).sourceTime();
		assertThat(result.stream().filter(c -> c.sourceTime().equals(cachedMinute)).findFirst().orElseThrow().close())
			.isEqualByComparingTo("1");
	}

	@Test
	@DisplayName("D-2 ⓑ: 캐시가 이미 200개를 채운 창 → 보충 위임이 호출되지 않는다")
	void d2SupplementNotCalledWhenCacheAlreadyFillsTwoHundred() {
		LocalDateTime to = NOW;
		LocalDateTime from = to.minusMinutes(199); // 정확히 200분 폭
		LocalDateTime since = NOW.minusDays(2);
		List<CryptoCandleDto> fullCached = candlesEveryMinute(from, to, "5"); // 200개 슬롯이 전부 채워짐
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
		LocalDateTime from = to.minusMinutes(199); // 정확히 200분 폭
		LocalDateTime since = NOW.minusDays(2);
		LocalDateTime overlapMinute = from.plusMinutes(50);
		List<CryptoCandleDto> sparseCached = List.of(
			candleAt(overlapMinute, "999"), // 위임 구간과 겹치는 시각 — 캐시 값이 이겨야 한다
			candleAt(to, "777")); // 캐시 전용 "진행 중" 봉 — 아직 마감 안 돼 빗썸엔 없는 가장 최신 분
		// 빗썸은 마감된 200개(from-1 ~ to-1)까지만 채워 오고, 아직 마감 안 된 to는 포함하지 않는다
		List<CryptoCandleDto> supplement = candlesEveryMinute(from.minusMinutes(1), to.minusMinutes(1), "1");
		when(candleStore.getSince("BTC")).thenReturn(Optional.of(since));
		when(candleStore.getCandles("BTC", from, to)).thenReturn(sparseCached);
		when(delegate.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(supplement);

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		assertThat(supplement).hasSize(200);
		assertThat(result).hasSize(200); // 201개(위임 200 + 캐시 전용 1) → 최신 200개로 잘림
		assertThat(result.get(0).sourceTime()).isEqualTo(from); // 가장 오래된 위임 봉(from-1)이 잘려나간다
		assertThat(result.get(result.size() - 1).sourceTime()).isEqualTo(to);
		assertThat(result.get(result.size() - 1).close()).isEqualByComparingTo("777"); // 캐시 전용 진행 중 봉이 살아남는다
		assertThat(result.stream()
			.filter(c -> c.sourceTime().equals(overlapMinute))
			.findFirst().orElseThrow().close())
			.isEqualByComparingTo("999"); // 겹치는 시각은 캐시 값이 이긴다
		verify(delegate, times(1)).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
	}
}
