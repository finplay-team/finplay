package com.finplay.api.domain.market.feed;

import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
@RequiredArgsConstructor
public class FakeBithumbFeedClient implements BithumbFeedClient {

	private final PriceStore priceStore;

	private volatile boolean connected;

	@Override
	public void start() {
		connected = true;
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@Override
	public void stop() {
		connected = false;
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	public void emitTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		priceStore.saveTick(symbol, price, receivedAt);
	}

	public void simulateDisconnect() {
		stop();
	}

	public void simulateReconnect() {
		start();
	}
}
