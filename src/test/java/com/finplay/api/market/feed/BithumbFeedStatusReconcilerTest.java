// 목 BithumbFeedClient·PriceStore로 BithumbFeedStatusReconciler의 재기록 조건을 검증하는 단위 테스트
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class BithumbFeedStatusReconcilerTest {

	@Mock
	private BithumbFeedClient bithumbFeedClient;

	@Mock
	private PriceStore priceStore;

	@Test
	void rewritesConnectedStatusWhenClientIsConnectedButStoreStatusIsNot() {
		when(bithumbFeedClient.isConnected()).thenReturn(true);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(bithumbFeedClient, priceStore);

		reconciler.reconcileConnectionStatus();

		verify(priceStore, times(1)).saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@Test
	void doesNothingWhenStoreStatusIsAlreadyConnected() {
		when(bithumbFeedClient.isConnected()).thenReturn(true);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(bithumbFeedClient, priceStore);

		reconciler.reconcileConnectionStatus();

		verify(priceStore, never()).saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	// fail-closed(MKT-004) 유지 — 클라이언트가 끊겨 있으면 Redis 상태를 손대지 않는다.
	@Test
	void doesNotTouchStoreWhenClientIsNotConnected() {
		when(bithumbFeedClient.isConnected()).thenReturn(false);
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(bithumbFeedClient, priceStore);

		reconciler.reconcileConnectionStatus();

		verify(priceStore, never()).getConnectionStatus();
		verify(priceStore, never()).saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@Test
	void doesNotPropagateWhenReadingStoreStatusFailsSoNextCycleCanRetry() {
		when(bithumbFeedClient.isConnected()).thenReturn(true);
		when(priceStore.getConnectionStatus())
			.thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(bithumbFeedClient, priceStore);

		assertThatCode(reconciler::reconcileConnectionStatus).doesNotThrowAnyException();
	}

	@Test
	void doesNotPropagateWhenWritingStoreStatusFailsSoNextCycleCanRetry() {
		when(bithumbFeedClient.isConnected()).thenReturn(true);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
			.when(priceStore)
			.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		BithumbFeedStatusReconciler reconciler = new BithumbFeedStatusReconciler(bithumbFeedClient, priceStore);

		assertThatCode(reconciler::reconcileConnectionStatus).doesNotThrowAnyException();
	}
}
