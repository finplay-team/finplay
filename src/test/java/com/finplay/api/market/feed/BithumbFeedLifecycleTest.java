// 목 BithumbFeedClient로 BithumbFeedLifecycle의 시작·종료 호출을 검증하는 단위 테스트
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class BithumbFeedLifecycleTest {

	@Mock
	private BithumbFeedClient bithumbFeedClient;

	@Test
	void startFeedCallsClientStartOnce() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient);

		lifecycle.startFeed();

		verify(bithumbFeedClient, times(1)).start();
		verifyNoMoreInteractions(bithumbFeedClient);
	}

	// 이슈 #288: FakeBithumbFeedClient.start()는 PriceStore.saveConnectionStatus로 Redis를 동기 호출한다 —
	// Redis가 죽어 있으면 이 예외가 ApplicationReadyEvent 리스너까지 전파돼 애플리케이션 기동 자체가 실패한다.
	// 캐시성 의존(Redis) 하나 때문에 전체 기동이 막히면 안 되므로, startFeed는 이 예외를 삼키고 로그만 남긴다.
	@Test
	void startFeedDoesNotPropagateWhenClientStartFailsSoApplicationStartupIsNotBlocked() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient);
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
			.when(bithumbFeedClient)
			.start();

		assertThatCode(lifecycle::startFeed).doesNotThrowAnyException();

		verify(bithumbFeedClient, times(1)).start();
	}

	@Test
	void stopFeedCallsClientStopOnceOnPreDestroy() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient);

		lifecycle.stopFeed();

		verify(bithumbFeedClient, times(1)).stop();
		verifyNoMoreInteractions(bithumbFeedClient);
	}
}
