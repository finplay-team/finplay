// 목 BithumbRestCandleProvider·CryptoCandleStore로 CachedCryptoCandleProvider의 구간 분할·캐시 우선·장애 폴백을 검증하는 단위 테스트
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.store.CryptoCandleStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
}
