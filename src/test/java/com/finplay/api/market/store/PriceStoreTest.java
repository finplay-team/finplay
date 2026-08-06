// mock Redis(StringRedisTemplate)로 PriceStore.getLatestPrices의 배치화(전역 연결상태 1회 조회)를 검증하는 단위 테스트다.
package com.finplay.api.market.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

class PriceStoreTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 12, 0, 0);
	private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	private static final String STATUS_KEY = "feed:crypto:status";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
	@SuppressWarnings("unchecked")
	private final HashOperations<String, String, String> hashOperations = mock(HashOperations.class);
	@SuppressWarnings("unchecked")
	private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private PriceStore priceStore() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		return spy(new PriceStore(redisTemplate, FIXED_CLOCK, eventPublisher));
	}

	private void stubTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		when(hashOperations.get("price:crypto:" + symbol, "price")).thenReturn(price.toPlainString());
		when(hashOperations.get("price:crypto:" + symbol, "receivedAt")).thenReturn(receivedAt.toString());
	}

	@Test
	void getLatestPricesQueriesConnectionStatusOnlyOnceForMultipleSymbols() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.CONNECTED.name());
		stubTick("BTC", new BigDecimal("50000000"), NOW.minusSeconds(2));
		stubTick("ETH", new BigDecimal("3000000"), NOW.minusSeconds(2));
		stubTick("XRP", new BigDecimal("700"), NOW.minusSeconds(2));

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("BTC", "ETH", "XRP"));

		assertThat(result).hasSize(3);
		assertThat(result.get("BTC").price()).isEqualByComparingTo("50000000");
		assertThat(result.get("ETH").price()).isEqualByComparingTo("3000000");
		assertThat(result.get("XRP").price()).isEqualByComparingTo("700");
		// 종목(심볼) 수(3개)와 무관하게 전역 연결상태 조회(getConnectionStatus)는 요청당 1회만 일어나야 한다
		// (PR #97 리뷰 권장사항 — 개별 isPriceAvailable/getLatestPrice 반복호출 시 발생하던 중복 제거).
		verify(priceStore, times(1)).getConnectionStatus();
		verify(valueOperations, times(1)).get(STATUS_KEY);
	}

	@Test
	void getLatestPricesReturnsEmptyMapImmediatelyWithoutPerSymbolLookupWhenDisconnected() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.DISCONNECTED.name());

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("BTC", "ETH"));

		assertThat(result).isEmpty();
		verify(priceStore, never()).getLatestPrice(any());
		verify(hashOperations, never()).get(any(), any());
	}

	@Test
	void getLatestPricesExcludesOnlyStaleSymbolsInMixedFreshnessBatch() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.CONNECTED.name());
		stubTick("FRESH", new BigDecimal("100"), NOW.minusSeconds(2));
		stubTick("STALE", new BigDecimal("200"), NOW.minusSeconds(11));

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("FRESH", "STALE"));

		assertThat(result).hasSize(1);
		assertThat(result).containsKey("FRESH");
		assertThat(result).doesNotContainKey("STALE");
	}

	@Test
	void getLatestPricesOmitsSymbolWithNoSavedTickFromResultMap() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.CONNECTED.name());
		when(hashOperations.get(eq("price:crypto:UNKNOWN"), any())).thenReturn(null);

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("UNKNOWN"));

		assertThat(result).isEmpty();
	}

	// 아래부터는 015-limit-order LMT-002 트리거(CryptoPriceUpdatedEvent publish) 검증이다.

	@Test
	void saveTickPublishesEventWhenNoExistingTickForSymbol() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:NEW_SYMBOL", "receivedAt")).thenReturn(null);
		LocalDateTime receivedAt = NOW.minusSeconds(1);

		priceStore.saveTick("NEW_SYMBOL", new BigDecimal("100"), receivedAt);

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		CryptoPriceUpdatedEvent event = eventCaptor.getValue();
		assertThat(event.symbol()).isEqualTo("NEW_SYMBOL");
		assertThat(event.price()).isEqualByComparingTo("100");
		assertThat(event.receivedAt()).isEqualTo(receivedAt);
	}

	@Test
	void saveTickPublishesEventWhenNewerTickReplacesExistingValue() {
		PriceStore priceStore = priceStore();
		LocalDateTime existing = NOW.minusSeconds(5);
		LocalDateTime newer = NOW.minusSeconds(1);
		stubTick("REPLACE", new BigDecimal("100"), existing);

		priceStore.saveTick("REPLACE", new BigDecimal("200"), newer);

		verify(eventPublisher).publishEvent(any(CryptoPriceUpdatedEvent.class));
	}

	@Test
	void saveTickDoesNotPublishEventWhenTickIsOlderThanExistingValue() {
		PriceStore priceStore = priceStore();
		LocalDateTime existing = NOW.minusSeconds(1);
		LocalDateTime older = existing.minusSeconds(5);
		stubTick("OLDER", new BigDecimal("100"), existing);

		priceStore.saveTick("OLDER", new BigDecimal("999"), older);

		verify(eventPublisher, never()).publishEvent(any());
		verify(hashOperations, never()).putAll(eq("price:crypto:OLDER"), any());
	}

	@Test
	void saveTickDoesNotPublishEventWhenTickIsSameInstantAsExistingValue() {
		PriceStore priceStore = priceStore();
		LocalDateTime same = NOW.minusSeconds(1);
		stubTick("SAME_INSTANT", new BigDecimal("100"), same);

		priceStore.saveTick("SAME_INSTANT", new BigDecimal("999"), same);

		verify(eventPublisher, never()).publishEvent(any());
		verify(hashOperations, never()).putAll(eq("price:crypto:SAME_INSTANT"), any());
	}

	@Test
	void saveTickPublishesOneEventPerSymbolWithItsOwnPayloadWhenMultipleSymbolsUpdateTogether() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:BTC", "receivedAt")).thenReturn(null);
		when(hashOperations.get("price:crypto:ETH", "receivedAt")).thenReturn(null);
		LocalDateTime btcReceivedAt = NOW.minusSeconds(2);
		LocalDateTime ethReceivedAt = NOW.minusSeconds(1);

		priceStore.saveTick("BTC", new BigDecimal("50000000"), btcReceivedAt);
		priceStore.saveTick("ETH", new BigDecimal("3000000"), ethReceivedAt);

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
		List<CryptoPriceUpdatedEvent> events = eventCaptor.getAllValues();

		CryptoPriceUpdatedEvent btcEvent = events.stream().filter(e -> e.symbol().equals("BTC")).findFirst()
			.orElseThrow();
		assertThat(btcEvent.price()).isEqualByComparingTo("50000000");
		assertThat(btcEvent.receivedAt()).isEqualTo(btcReceivedAt);

		CryptoPriceUpdatedEvent ethEvent = events.stream().filter(e -> e.symbol().equals("ETH")).findFirst()
			.orElseThrow();
		assertThat(ethEvent.price()).isEqualByComparingTo("3000000");
		assertThat(ethEvent.receivedAt()).isEqualTo(ethReceivedAt);
	}

	// 아래부터는 spec 012 §코인 가격 스냅샷(이슈 #225) — recordSnapshot·getSnapshots 검증이다.

	@Test
	void recordSnapshotAddsMemberWithEpochMillisScoreAndPriceEncodedInMember() {
		PriceStore priceStore = priceStore();
		LocalDateTime recordedAt = NOW.minusMinutes(1);
		long expectedEpochMillis = recordedAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();

		priceStore.recordSnapshot("BTC", recordedAt, new BigDecimal("50000000"), Duration.ofHours(24));

		verify(zSetOperations).add(
			"price:crypto:BTC:snapshots", expectedEpochMillis + ":50000000", expectedEpochMillis);
	}

	// 함정 검증 — recordSnapshot은 수익률이 아니라 "가격+시각"을 member 문자열에 그대로 담아야 한다. 수익률만
	// 저장하면 나중에 "N분 전 가격"을 복원할 수 없다(tasks.md 항목 1 함정).
	@Test
	void recordSnapshotEncodesRawPriceNotAReturnRatio() {
		PriceStore priceStore = priceStore();
		LocalDateTime recordedAt = NOW;
		long expectedEpochMillis = recordedAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();

		priceStore.recordSnapshot("ETH", recordedAt, new BigDecimal("3000000.50"), Duration.ofHours(24));

		ArgumentCaptor<String> memberCaptor = ArgumentCaptor.forClass(String.class);
		verify(zSetOperations).add(eq("price:crypto:ETH:snapshots"), memberCaptor.capture(), anyDouble());
		String member = memberCaptor.getValue();
		String encodedPrice = member.substring(member.indexOf(':') + 1);
		assertThat(new BigDecimal(encodedPrice)).isEqualByComparingTo("3000000.50");
		assertThat(member).startsWith(expectedEpochMillis + ":");
	}

	// 가지치기 — retention을 넘은 과거 원소를 기록할 때마다 제거한다(tasks.md 항목 1 "sigma-lookback-hours를
	// 넘은 원소를 기록할 때마다 제거한다"). cutoff = recordedAt - retention 이전 구간을 제거 대상으로 넘겨야 한다.
	@Test
	void recordSnapshotPrunesElementsOlderThanRetentionOnEveryRecord() {
		PriceStore priceStore = priceStore();
		LocalDateTime recordedAt = NOW;
		Duration retention = Duration.ofHours(24);
		long expectedCutoffMillis = recordedAt.minus(retention).atZone(ZoneOffset.UTC).toInstant().toEpochMilli();

		priceStore.recordSnapshot("BTC", recordedAt, new BigDecimal("100"), retention);

		verify(zSetOperations).removeRangeByScore(
			"price:crypto:BTC:snapshots", Double.NEGATIVE_INFINITY, expectedCutoffMillis);
	}

	@Test
	void getSnapshotsReturnsEmptyListWhenNoMemberExistsInRange() {
		PriceStore priceStore = priceStore();
		when(zSetOperations.rangeByScore(eq("price:crypto:BTC:snapshots"), anyDouble(), anyDouble()))
			.thenReturn(Set.of());

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots("BTC", NOW.minusHours(1), NOW);

		assertThat(snapshots).isEmpty();
	}

	@Test
	void getSnapshotsParsesMembersAndSortsByRecordedAtAscending() {
		PriceStore priceStore = priceStore();
		LocalDateTime earlier = NOW.minusMinutes(10);
		LocalDateTime later = NOW.minusMinutes(5);
		long earlierMillis = earlier.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
		long laterMillis = later.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
		// 저장 순서와 반대로 반환돼도(later가 먼저) 결과는 시각 오름차순이어야 한다.
		when(zSetOperations.rangeByScore(eq("price:crypto:BTC:snapshots"), anyDouble(), anyDouble()))
			.thenReturn(Set.of(laterMillis + ":200", earlierMillis + ":100"));

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots("BTC", earlier, later);

		assertThat(snapshots).hasSize(2);
		assertThat(snapshots.get(0).recordedAt()).isEqualTo(earlier);
		assertThat(snapshots.get(0).price()).isEqualByComparingTo("100");
		assertThat(snapshots.get(1).recordedAt()).isEqualTo(later);
		assertThat(snapshots.get(1).price()).isEqualByComparingTo("200");
	}
}
