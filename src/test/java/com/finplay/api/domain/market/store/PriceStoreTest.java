package com.finplay.api.domain.market.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
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

	@Test
	void recordObservationUpdatesObservedAtOnlyWhenPriceUnchanged() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:OBS_SAME", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_SAME", "receivedAt")).thenReturn(NOW.minusSeconds(5).toString());

		priceStore.recordObservation("OBS_SAME", new BigDecimal("100"), NOW);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations).putAll(eq("price:crypto:OBS_SAME"), fieldsCaptor.capture());
		Map<String, String> fields = fieldsCaptor.getValue();
		assertThat(fields).containsEntry("observedAt", NOW.toString());
		assertThat(fields).doesNotContainKey("price");
		assertThat(fields).doesNotContainKey("receivedAt");
	}

	@Test
	void recordObservationUpdatesPriceWhenDifferentButLeavesReceivedAtFieldUntouched() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:OBS_DIFF", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_DIFF", "receivedAt")).thenReturn(NOW.minusSeconds(5).toString());

		priceStore.recordObservation("OBS_DIFF", new BigDecimal("150"), NOW);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations).putAll(eq("price:crypto:OBS_DIFF"), fieldsCaptor.capture());
		Map<String, String> fields = fieldsCaptor.getValue();
		assertThat(fields).containsEntry("observedAt", NOW.toString());
		assertThat(fields).containsEntry("price", "150");
		assertThat(fields).doesNotContainKey("receivedAt");
	}

	@Test
	void isStaleIsFalseWhenObservedAtIsFreshEvenIfReceivedAtIsVeryOld() {
		PriceStore priceStore = priceStore();
		LocalDateTime oldReceivedAt = NOW.minusMinutes(5);
		LocalDateTime freshObservedAt = NOW.minusSeconds(1);
		when(hashOperations.get("price:crypto:OBS_FRESH", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_FRESH", "receivedAt")).thenReturn(oldReceivedAt.toString());
		when(hashOperations.get("price:crypto:OBS_FRESH", "observedAt")).thenReturn(freshObservedAt.toString());

		CryptoPriceDto result = priceStore.getLatestPrice("OBS_FRESH").orElseThrow();

		assertThat(result.receivedAt()).isEqualTo(oldReceivedAt);
		assertThat(result.observedAt()).isEqualTo(freshObservedAt);
		assertThat(priceStore.isStale(result.observedAt())).isFalse();
	}

	@Test
	void getLatestPriceFallsBackToReceivedAtWhenObservedAtFieldIsMissingFromExistingHash() {
		PriceStore priceStore = priceStore();
		LocalDateTime receivedAt = NOW.minusSeconds(3);
		when(hashOperations.get("price:crypto:OBS_LEGACY", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_LEGACY", "receivedAt")).thenReturn(receivedAt.toString());
		when(hashOperations.get("price:crypto:OBS_LEGACY", "observedAt")).thenReturn(null);

		CryptoPriceDto result = priceStore.getLatestPrice("OBS_LEGACY").orElseThrow();

		assertThat(result.observedAt()).isEqualTo(receivedAt);
	}

	@Test
	void saveTickStillAppliesTickWithReceivedAtEarlierThanJustRecordedObservationTime() {
		PriceStore priceStore = priceStore();
		LocalDateTime oldReceivedAt = NOW.minusSeconds(20);
		stubTick("OBS_RACE", new BigDecimal("100"), oldReceivedAt);

		priceStore.recordObservation("OBS_RACE", new BigDecimal("100"), NOW);

		LocalDateTime tradeReceivedAt = NOW.minusSeconds(15);
		priceStore.saveTick("OBS_RACE", new BigDecimal("101"), tradeReceivedAt);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations, atLeastOnce()).putAll(eq("price:crypto:OBS_RACE"), fieldsCaptor.capture());
		Map<String, String> lastFields = fieldsCaptor.getAllValues().get(fieldsCaptor.getAllValues().size() - 1);
		assertThat(lastFields.get("price")).isEqualTo("101");
		assertThat(lastFields.get("receivedAt")).isEqualTo(tradeReceivedAt.toString());
	}

	@Test
	void recordObservationDoesNotPublishEventWhenOnlyObservedAtChanges() {
		PriceStore priceStore = priceStore();
		LocalDateTime existingReceivedAt = NOW.minusSeconds(5);
		stubTick("OBS_EVENT_SAME", new BigDecimal("100"), existingReceivedAt);

		priceStore.recordObservation("OBS_EVENT_SAME", new BigDecimal("100"), NOW);

		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void recordObservationPublishesEventWithExistingReceivedAtWhenPriceActuallyChanges() {
		PriceStore priceStore = priceStore();
		LocalDateTime existingReceivedAt = NOW.minusSeconds(5);
		stubTick("OBS_EVENT_DIFF", new BigDecimal("100"), existingReceivedAt);

		priceStore.recordObservation("OBS_EVENT_DIFF", new BigDecimal("150"), NOW);

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		CryptoPriceUpdatedEvent event = eventCaptor.getValue();
		assertThat(event.symbol()).isEqualTo("OBS_EVENT_DIFF");
		assertThat(event.price()).isEqualByComparingTo("150");
		assertThat(event.receivedAt()).isEqualTo(existingReceivedAt);
	}

	@Test
	void recordObservationBootstrapsReceivedAtAndPublishesEventWhenSymbolHasNeverBeenObserved() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP", "price")).thenReturn(null);
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP", "receivedAt")).thenReturn(null);

		priceStore.recordObservation("OBS_BOOTSTRAP", new BigDecimal("100"), NOW);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations).putAll(eq("price:crypto:OBS_BOOTSTRAP"), fieldsCaptor.capture());
		Map<String, String> fields = fieldsCaptor.getValue();
		assertThat(fields).containsEntry("price", "100");
		assertThat(fields).containsEntry("observedAt", NOW.toString());
		assertThat(fields).containsEntry("receivedAt", NOW.toString());

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		CryptoPriceUpdatedEvent event = eventCaptor.getValue();
		assertThat(event.symbol()).isEqualTo("OBS_BOOTSTRAP");
		assertThat(event.price()).isEqualByComparingTo("100");
		assertThat(event.receivedAt()).isEqualTo(NOW);
		assertThat(event.observedAt()).isEqualTo(NOW);
	}

	@Test
	void saveTickOverwritesBootstrappedReceivedAtWhenRealTickArrivesLater() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP_THEN_TICK", "price")).thenReturn(null);
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP_THEN_TICK", "receivedAt")).thenReturn(null);

		priceStore.recordObservation("OBS_BOOTSTRAP_THEN_TICK", new BigDecimal("100"), NOW);

		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP_THEN_TICK", "receivedAt")).thenReturn(NOW.toString());

		LocalDateTime realReceivedAt = NOW.plusSeconds(2);
		priceStore.saveTick("OBS_BOOTSTRAP_THEN_TICK", new BigDecimal("105"), realReceivedAt);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations, atLeastOnce()).putAll(eq("price:crypto:OBS_BOOTSTRAP_THEN_TICK"),
			fieldsCaptor.capture());
		Map<String, String> lastFields = fieldsCaptor.getAllValues().get(fieldsCaptor.getAllValues().size() - 1);
		assertThat(lastFields.get("price")).isEqualTo("105");
		assertThat(lastFields.get("receivedAt")).isEqualTo(realReceivedAt.toString());
	}

	@Test
	void recordSnapshotAddsMemberWithEpochMillisScoreAndPriceEncodedInMember() {
		PriceStore priceStore = priceStore();
		LocalDateTime recordedAt = NOW.minusMinutes(1);
		long expectedEpochMillis = recordedAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();

		priceStore.recordSnapshot("BTC", recordedAt, new BigDecimal("50000000"), Duration.ofHours(24));

		verify(zSetOperations).add(
			"price:crypto:BTC:snapshots", expectedEpochMillis + ":50000000", expectedEpochMillis);
	}

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
