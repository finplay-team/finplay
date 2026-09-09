package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.store.CryptoPriceDto;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FakeBithumbFeedClientIntegrationTest {

	private static final String STATUS_KEY = "feed:crypto:status";
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 28, 12, 0, 0);

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete(STATUS_KEY);
		redisTemplate.delete("price:crypto:ETH_EMIT");
		redisTemplate.delete("price:crypto:ETH_DISCONNECT");
		redisTemplate.delete("price:crypto:ETH_RECONNECT");
	}

	private PriceStore priceStoreAt(LocalDateTime now) {
		Clock fixedClock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		return new PriceStore(redisTemplate, fixedClock, eventPublisher);
	}

	@Test
	void emitTickSavesPriceIntoPriceStore() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		FakeBithumbFeedClient feedClient = new FakeBithumbFeedClient(priceStore);

		feedClient.emitTick("ETH_EMIT", new BigDecimal("3000000"), FIXED_NOW.minusSeconds(1));

		CryptoPriceDto result = priceStore.getLatestPrice("ETH_EMIT").orElseThrow();
		assertThat(result.price()).isEqualByComparingTo("3000000");
		assertThat(result.receivedAt()).isEqualTo(FIXED_NOW.minusSeconds(1));
	}

	@Test
	void simulateDisconnectMakesPriceUnavailableEvenWithFreshTick() {
		PriceStore priceStore = priceStoreAt(FIXED_NOW);
		FakeBithumbFeedClient feedClient = new FakeBithumbFeedClient(priceStore);

		feedClient.start();
		feedClient.emitTick("ETH_DISCONNECT", new BigDecimal("3000000"), FIXED_NOW.minusSeconds(1));
		assertThat(priceStore.isPriceAvailable("ETH_DISCONNECT")).isTrue();

		feedClient.simulateDisconnect();

		assertThat(feedClient.isConnected()).isFalse();
		assertThat(priceStore.getConnectionStatus()).isEqualTo(FeedConnectionStatus.DISCONNECTED);
		assertThat(priceStore.isPriceAvailable("ETH_DISCONNECT")).isFalse();
	}

	@Test
	void simulateReconnectAloneDoesNotRestoreAvailabilityUntilNewTickArrives() {
		PriceStore priceStoreAtT0 = priceStoreAt(FIXED_NOW);
		FakeBithumbFeedClient feedClient = new FakeBithumbFeedClient(priceStoreAtT0);

		feedClient.start();
		feedClient.emitTick("ETH_RECONNECT", new BigDecimal("3000000"), FIXED_NOW.minusSeconds(1));
		assertThat(priceStoreAtT0.isPriceAvailable("ETH_RECONNECT")).isTrue();

		feedClient.simulateDisconnect();
		assertThat(priceStoreAtT0.isPriceAvailable("ETH_RECONNECT")).isFalse();

		feedClient.simulateReconnect();
		assertThat(feedClient.isConnected()).isTrue();
		assertThat(priceStoreAtT0.getConnectionStatus()).isEqualTo(FeedConnectionStatus.CONNECTED);

		LocalDateTime t1 = FIXED_NOW.plusSeconds(15);
		PriceStore priceStoreAtT1 = priceStoreAt(t1);
		assertThat(priceStoreAtT1.isPriceAvailable("ETH_RECONNECT")).isFalse();

		feedClient.emitTick("ETH_RECONNECT", new BigDecimal("3100000"), t1);

		assertThat(priceStoreAtT1.isPriceAvailable("ETH_RECONNECT")).isTrue();
	}
}
