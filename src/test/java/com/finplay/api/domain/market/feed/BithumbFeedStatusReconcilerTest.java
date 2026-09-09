package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
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
