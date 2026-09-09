package com.finplay.api.domain.market.feed;

import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "bithumb.feed.reconciler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BithumbFeedStatusReconciler {

	private static final long RECONCILE_INTERVAL_MS = 15_000;

	private final BithumbFeedClient bithumbFeedClient;
	private final PriceStore priceStore;

	@Scheduled(fixedRate = RECONCILE_INTERVAL_MS)
	public void reconcileConnectionStatus() {
		if (!bithumbFeedClient.isConnected()) {
			return;
		}
		try {
			if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
				priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
				log.info("빗썸 연결상태 키(feed:crypto:status)를 CONNECTED로 재기록했습니다.");
			}
		} catch (Exception e) {
			log.warn("빗썸 연결상태 재기록 실패(Redis 장애로 추정) — 다음 주기에 재시도합니다.", e);
		}
	}
}
