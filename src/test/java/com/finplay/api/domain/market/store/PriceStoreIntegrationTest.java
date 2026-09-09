package com.finplay.api.domain.market.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PriceStoreIntegrationTest {

	private static final String STATUS_KEY = "feed:crypto:status";
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 28, 12, 0, 0);

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@BeforeEach
	void deleteConnectionStatusBeforeEachTest() {
		redisTemplate.delete(STATUS_KEY);
	}

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete(STATUS_KEY);
		redisTemplate.delete("price:crypto:BTC_BASIC");
		redisTemplate.delete("price:crypto:BTC_OLD_TICK");
		redisTemplate.delete("price:crypto:BTC_NEW_TICK");
		redisTemplate.delete("price:crypto:BTC_STALE_CHECK");
		redisTemplate.delete("price:crypto:BTC_AVAILABLE");
		redisTemplate.delete("price:crypto:BTC_DISCONNECTED");
		redisTemplate.delete("price:crypto:BTC_CONNECTED_STALE");
		redisTemplate.delete("price:crypto:BTC_NO_STATUS_YET");
		redisTemplate.delete("price:crypto:BTC_SNAPSHOT:snapshots");
		redisTemplate.delete("price:crypto:BTC_PRUNE:snapshots");
		redisTemplate.delete("price:crypto:BTC_OBS_BOOTSTRAP");
	}

	private PriceStore priceStoreAt(LocalDateTime now) {
		Clock fixedClock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		return new PriceStore(redisTemplate, fixedClock, eventPublisher);
	}

	@Test
	void saveTickThenGetLatestPriceReturnsSavedPriceAndReceivedAt() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		LocalDateTime receivedAt = FIXED_NOW.minusSeconds(1);

		priceStore.saveTick("BTC_BASIC", new BigDecimal("123456700.00"), receivedAt);

		CryptoPriceDto result = priceStore.getLatestPrice("BTC_BASIC").orElseThrow();
		assertThat(result.symbol()).isEqualTo("BTC_BASIC");
		assertThat(result.price()).isEqualByComparingTo("123456700.00");
		assertThat(result.receivedAt()).isEqualTo(receivedAt);
	}

	@Test
	void saveTickIgnoresOlderTickAndKeepsExistingValue() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		LocalDateTime latest = FIXED_NOW.minusSeconds(1);
		LocalDateTime older = latest.minusSeconds(5);

		priceStore.saveTick("BTC_OLD_TICK", new BigDecimal("100"), latest);
		priceStore.saveTick("BTC_OLD_TICK", new BigDecimal("999"), older);

		CryptoPriceDto result = priceStore.getLatestPrice("BTC_OLD_TICK").orElseThrow();
		assertThat(result.price()).isEqualByComparingTo("100");
		assertThat(result.receivedAt()).isEqualTo(latest);
	}

	@Test
	void saveTickOverwritesWithNewerTick() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		LocalDateTime first = FIXED_NOW.minusSeconds(5);
		LocalDateTime second = FIXED_NOW.minusSeconds(1);

		priceStore.saveTick("BTC_NEW_TICK", new BigDecimal("100"), first);
		priceStore.saveTick("BTC_NEW_TICK", new BigDecimal("200"), second);

		CryptoPriceDto result = priceStore.getLatestPrice("BTC_NEW_TICK").orElseThrow();
		assertThat(result.price()).isEqualByComparingTo("200");
		assertThat(result.receivedAt()).isEqualTo(second);
	}

	@Test
	void isStaleReturnsFalseWithinTenSeconds() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);

		assertThat(priceStore.isStale(FIXED_NOW.minusSeconds(10))).isFalse();
		assertThat(priceStore.isStale(FIXED_NOW.minusSeconds(5))).isFalse();
	}

	@Test
	void isStaleReturnsTrueAfterTenSeconds() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);

		assertThat(priceStore.isStale(FIXED_NOW.minusSeconds(11))).isTrue();
	}

	@Test
	void isPriceAvailableReturnsTrueWhenConnectedAndFresh() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick("BTC_AVAILABLE", new BigDecimal("100"), FIXED_NOW.minusSeconds(2));

		assertThat(priceStore.isPriceAvailable("BTC_AVAILABLE")).isTrue();
	}

	@Test
	void isPriceAvailableReturnsFalseWhenDisconnected() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
		priceStore.saveTick("BTC_DISCONNECTED", new BigDecimal("100"), FIXED_NOW.minusSeconds(2));

		assertThat(priceStore.isPriceAvailable("BTC_DISCONNECTED")).isFalse();
	}

	@Test
	void isPriceAvailableReturnsFalseWhenConnectedButStale() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick("BTC_CONNECTED_STALE", new BigDecimal("100"), FIXED_NOW.minusSeconds(11));

		assertThat(priceStore.isPriceAvailable("BTC_CONNECTED_STALE")).isFalse();
	}

	@Test
	void connectionStatusDefaultsToDisconnectedBeforeAnySave() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);

		assertThat(priceStore.getConnectionStatus()).isEqualTo(FeedConnectionStatus.DISCONNECTED);
		assertThat(priceStore.isPriceAvailable("BTC_NO_STATUS_YET")).isFalse();
	}

	@Test
	void recordObservationOnNeverObservedSymbolMakesGetLatestPriceReturnPresent() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);

		assertThat(priceStore.getLatestPrice("BTC_OBS_BOOTSTRAP")).isEmpty();

		priceStore.recordObservation("BTC_OBS_BOOTSTRAP", new BigDecimal("100"), FIXED_NOW);

		CryptoPriceDto result = priceStore.getLatestPrice("BTC_OBS_BOOTSTRAP").orElseThrow();
		assertThat(result.price()).isEqualByComparingTo("100");
		assertThat(result.receivedAt()).isEqualTo(FIXED_NOW);
		assertThat(result.observedAt()).isEqualTo(FIXED_NOW);
	}

	@Test
	void recordSnapshotThenGetSnapshotsReturnsStoredPriceAndTimeInAscendingOrder() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		LocalDateTime older = FIXED_NOW.minusMinutes(10);
		LocalDateTime newer = FIXED_NOW.minusMinutes(5);

		priceStore.recordSnapshot("BTC_SNAPSHOT", newer, new BigDecimal("200"), Duration.ofHours(24));
		priceStore.recordSnapshot("BTC_SNAPSHOT", older, new BigDecimal("100"), Duration.ofHours(24));

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots("BTC_SNAPSHOT", older, newer);

		assertThat(snapshots).hasSize(2);
		assertThat(snapshots.get(0).recordedAt()).isEqualTo(older);
		assertThat(snapshots.get(0).price()).isEqualByComparingTo("100");
		assertThat(snapshots.get(1).recordedAt()).isEqualTo(newer);
		assertThat(snapshots.get(1).price()).isEqualByComparingTo("200");
	}

	@Test
	void getSnapshotsReturnsEmptyListWhenNothingRecordedForSymbol() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots(
			"BTC_SNAPSHOT_EMPTY", FIXED_NOW.minusHours(1), FIXED_NOW);

		assertThat(snapshots).isEmpty();
	}

	@Test
	void recordSnapshotActuallyPrunesElementsOlderThanRetentionFromRealRedis() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		Duration retention = Duration.ofHours(24);
		LocalDateTime old = FIXED_NOW.minusHours(30);
		LocalDateTime recent = FIXED_NOW.minusHours(1);

		priceStore.recordSnapshot("BTC_PRUNE", old, new BigDecimal("100"), retention);
		priceStore.recordSnapshot("BTC_PRUNE", recent, new BigDecimal("200"), retention);

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots(
			"BTC_PRUNE", old.minusDays(1), FIXED_NOW);

		assertThat(snapshots).hasSize(1);
		assertThat(snapshots.get(0).recordedAt()).isEqualTo(recent);
		assertThat(snapshots.get(0).price()).isEqualByComparingTo("200");
	}
}
