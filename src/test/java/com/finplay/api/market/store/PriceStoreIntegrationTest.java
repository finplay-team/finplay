// 실제 Redis(Testcontainers)로 코인 시세 저장·조회·stale 판정·주문 가능 여부를 검증하는 통합 테스트 (ADR-0003)
package com.finplay.api.market.store;

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

	// BithumbFeedLifecycle이 ApplicationReadyEvent에서 STATUS_KEY를 CONNECTED로 1회 설정하므로(이슈 #104), 공유
	// 컨텍스트에서 이 클래스의 테스트가 먼저 실행되면 fail-closed 기본값(DISCONNECTED) 전제가 흔들릴 수 있다 —
	// 테스트 시작 전에도 명시적으로 지워 순서와 무관하게 만든다(PR #110 리뷰 참고사항).
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

	// 아래부터는 spec 012 §코인 가격 스냅샷(이슈 #225) — 실제 Redis Sorted Set에 적재·조회되는지 검증한다.

	@Test
	void recordSnapshotThenGetSnapshotsReturnsStoredPriceAndTimeInAscendingOrder() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		LocalDateTime older = FIXED_NOW.minusMinutes(10);
		LocalDateTime newer = FIXED_NOW.minusMinutes(5);

		// 저장 순서를 뒤집어도(newer 먼저) 조회 결과는 시각 오름차순이어야 한다.
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

	// 가지치기 — retention(sigma-lookback-hours)을 넘은 원소는 기록할 때마다 실제로 제거된다
	// (tasks.md 항목 1 함정). mock으로는 removeRangeByScore 호출 여부만 보이지만, 여기서는 제거된 원소가
	// 실제로 조회에서 빠지는지까지 확인한다.
	@Test
	void recordSnapshotActuallyPrunesElementsOlderThanRetentionFromRealRedis() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		Duration retention = Duration.ofHours(24);
		LocalDateTime old = FIXED_NOW.minusHours(30);
		LocalDateTime recent = FIXED_NOW.minusHours(1);

		priceStore.recordSnapshot("BTC_PRUNE", old, new BigDecimal("100"), retention);
		// recent 기록 시 cutoff = recent - 24h = FIXED_NOW - 25h 이므로, old(FIXED_NOW - 30h)는 제거 대상이다.
		priceStore.recordSnapshot("BTC_PRUNE", recent, new BigDecimal("200"), retention);

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots(
			"BTC_PRUNE", old.minusDays(1), FIXED_NOW);

		assertThat(snapshots).hasSize(1);
		assertThat(snapshots.get(0).recordedAt()).isEqualTo(recent);
		assertThat(snapshots.get(0).price()).isEqualByComparingTo("200");
	}
}
