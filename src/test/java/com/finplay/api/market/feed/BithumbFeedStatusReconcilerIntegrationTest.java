// 실제 Redis(Testcontainers)로 BithumbFeedStatusReconciler의 연결상태 키 재기록을 검증하는 통합 테스트 (ADR-0003, 이슈 #299)
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.store.FeedConnectionStatus;
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
	private BithumbFeedStatusReconciler reconciler;

	@Autowired
	private FakeBithumbFeedClient feedClient;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete(STATUS_KEY);
	}

	@Test
	void rewritesStatusKeyWhenFeedClientIsConnectedButRedisLostTheKey() {
		feedClient.start();
		// Redis 재시작으로 상태 키만 사라진 상황을 재현한다 — feedClient는 여전히 연결된 상태다 (이슈 #299 재현 절차).
		redisTemplate.delete(STATUS_KEY);
		assertThat(redisTemplate.hasKey(STATUS_KEY)).isFalse();

		reconciler.reconcileConnectionStatus();

		assertThat(redisTemplate.opsForValue().get(STATUS_KEY)).isEqualTo(FeedConnectionStatus.CONNECTED.name());
	}

	@Test
	void doesNotWriteStatusKeyWhenFeedClientIsDisconnected() {
		feedClient.simulateDisconnect();
		redisTemplate.delete(STATUS_KEY);

		reconciler.reconcileConnectionStatus();

		assertThat(redisTemplate.hasKey(STATUS_KEY)).isFalse();
	}
}
