// 실제 Redis(Testcontainers)로 BithumbFeedStatusReconciler의 연결상태 키 재기록을 검증하는 통합 테스트 (ADR-0003, 이슈 #299)
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
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
		// Spring이 관리하는 싱글턴 FakeBithumbFeedClient를 건드리면 같은 캐시된 컨텍스트를 공유하는 다른
		// 테스트에 영향을 주므로, FakeBithumbFeedClientIntegrationTest와 동일하게 테스트 전용 인스턴스를 만든다.
		FakeBithumbFeedClient feedClient = new FakeBithumbFeedClient(priceStore);
		feedClient.start();
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(feedClient, priceStore);
		// Redis 재시작으로 상태 키만 사라진 상황을 재현한다 — feedClient는 여전히 연결된 상태다 (이슈 #299 재현 절차).
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
