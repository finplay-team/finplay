package com.finplay.api.domain.ranking.listener;

import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class RankingEventListener {

	private final RankingService rankingService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onRealizedPnlUpdated(RealizedPnlUpdatedEvent event) {
		try {
			rankingService.refreshScore(event.accountId());
		} catch (Exception e) {
			log.error("랭킹 갱신 처리 중 예외 발생. accountId={}", event.accountId(), e);
		}
	}
}
