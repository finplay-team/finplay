package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BithumbFeedStatusReconcilerIntegrationTest {

	private static final String STATUS_KEY = "feed:crypto:status";

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete(STATUS_KEY);
	}

	@Test
	void rewritesStatusKeyWhenFeedClientIsConnectedButRedisLostTheKey() {
		FakeBithumbFeedClient feedClient = new FakeBithumbFeedClient(priceStore);
		feedClient.start();
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(feedClient, priceStore);
		redisTemplate.delete(STATUS_KEY);
		assertThat(redisTemplate.hasKey(STATUS_KEY)).isFalse();

		reconciler.reconcileConnectionStatus();

		assertThat(redisTemplate.opsForValue().get(STATUS_KEY)).isEqualTo(FeedConnectionStatus.CONNECTED.name());
	}

	@Test
	void doesNotWriteStatusKeyWhenFeedClientIsDisconnected() {
		FakeBithumbFeedClient feedClient = new FakeBithumbFeedClient(priceStore);
		feedClient.simulateDisconnect();
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(feedClient, priceStore);
		redisTemplate.delete(STATUS_KEY);

		reconciler.reconcileConnectionStatus();

		assertThat(redisTemplate.hasKey(STATUS_KEY)).isFalse();
	}
}
