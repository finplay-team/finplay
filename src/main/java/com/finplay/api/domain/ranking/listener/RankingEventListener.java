// 계좌 실현손익 갱신 이벤트를 커밋 이후(after-commit)에 구독해 랭킹 점수를 갱신하는 리스너
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

	// 메서드 본문 전체를 try/catch로 감싼다 — RankingStore 내부의 try/catch는 Redis 호출만 방어하므로,
	// 그 앞단인 RankingService.refreshScore의 DB 재조회 실패(DataAccessException 등)까지 포함해
	// 어떤 예외든 이 리스너 밖(매도 체결을 처리한 원래 호출 스레드)으로 전파되지 않게 한다(plan.md 82행 근거).
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onRealizedPnlUpdated(RealizedPnlUpdatedEvent event) {
		try {
			rankingService.refreshScore(event.accountId());
		} catch (Exception e) {
			log.error("랭킹 갱신 처리 중 예외 발생. accountId={}", event.accountId(), e);
		}
	}
}
