// 피드 클라이언트가 실제로 연결돼 있는데 Redis 연결상태 키만 비어 있으면 다시 써서 자가치유하는 컴포넌트 (이슈 #299)
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

	// bithumbFeedClient.isConnected()가 true인데 Redis 상태 키가 CONNECTED가 아니면 다시 쓴다 — 기동 시점
	// saveConnectionStatus 실패(이슈 #299 재현 절차)나 Redis 단독 재시작으로 키가 사라진 경우를 프로세스
	// 재시작 없이 복구한다. isConnected()가 false면 아무것도 쓰지 않는다 — fail-closed(MKT-004)를 그대로
	// 유지하며, 판단 근거는 항상 isConnected() 하나뿐이다. Redis 읽기·쓰기 실패는 로그만 남기고 다음
	// 주기에 자연히 재시도한다(Fake·WebSocket 두 BithumbFeedClient 구현 모두에 프로필 제한 없이 적용된다).
	// @SpringBootTest 전체 컨텍스트에서는 bithumb-feed-reconciler-disabled-for-tests.yml이 bithumb.feed.reconciler.enabled를
	// false로 낮춰 이 빈 자체가 생성되지 않게 한다 — BithumbFeedSimulator와 동일한 컨벤션(PR #110 리뷰).
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
