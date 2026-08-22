// 실제 빗썸 WebSocket 연결 없이 임의 심볼의 틱 주입·연결 끊김/재연결을 시뮬레이션하는 테스트·로컬용 구현
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

	// 테스트 전용: 임의 심볼의 틱을 PriceStore에 직접 주입한다 (과거 틱 무시 규칙은 PriceStore가 적용).
	public void emitTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		priceStore.saveTick(symbol, price, receivedAt);
	}

	// 테스트 전용: 연결 끊김을 시뮬레이션한다 — 이후 해당 코인은 PriceStore.isPriceAvailable()이 false를 반환한다.
	public void simulateDisconnect() {
		stop();
	}

	// 테스트 전용: 재연결을 시뮬레이션한다. 재연결 자체만으로는 복귀하지 않으며, 이후 emitTick으로 새 틱을 받아야 가격이 유효해진다 (MKT-004).
	public void simulateReconnect() {
		start();
	}
}
