// mock Redis(StringRedisTemplate)로 PriceStore.getLatestPrices의 배치화(전역 연결상태 1회 조회)를 검증하는 단위 테스트다.
package com.finplay.api.market.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PriceStoreTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 12, 0, 0);
	private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	private static final String STATUS_KEY = "feed:crypto:status";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
	@SuppressWarnings("unchecked")
	private final HashOperations<String, String, String> hashOperations = mock(HashOperations.class);

	private PriceStore priceStore() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
		return spy(new PriceStore(redisTemplate, FIXED_CLOCK));
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
}
