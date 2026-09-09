package com.finplay.api.domain.market.feed;

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
