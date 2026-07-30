// 목 BithumbFeedClient로 BithumbFeedLifecycle의 시작·종료 호출을 검증하는 단위 테스트
package com.finplay.api.market.feed;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
	void stopFeedCallsClientStopOnceOnPreDestroy() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient);

		lifecycle.stopFeed();

		verify(bithumbFeedClient, times(1)).stop();
		verifyNoMoreInteractions(bithumbFeedClient);
	}
}
