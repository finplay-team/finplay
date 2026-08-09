// 매도 이력 원장(trades)과 계좌 실현손익(accounts)에서 랭킹 ZSET을 통째로 재구성하는 서비스 (이슈 #279)
package com.finplay.api.ranking.service;

import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.ranking.dto.RankingEntryDto;
import com.finplay.api.ranking.store.RankingStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// 레이어 배치(ADR-0002): ranking 도메인은 TradeRepository·AccountRepository를 직접 주입하지 않고 각 도메인의
// service를 경유한다. order 도메인이 RealizedPnlUpdatedEvent를 발행하는 방향과 반대라 순환 참조로 보일 수
// 있으나 타입 수준 순환이 아니다 — OrderExecutionService는 ApplicationEventPublisher와 account 도메인의
// 이벤트 record만 참조하고 ranking 패키지의 어떤 빈도 주입하지 않는다(plan.md "레이어 배치").
//
// 이 클래스에는 @Transactional을 붙이지 않는다. DB 조회는 TradeService·AccountService가 각자 readOnly
// 트랜잭션 안에서 끝내므로, 그 사이의 Redis 왕복 동안 DB 커넥션을 쥐지 않는다. @Scheduled 메서드에
// @Transactional을 겹칠 때 생기는 프록시·self-invocation 혼선도 피한다.
//
// 분산 락은 두지 않는다 — 재구성은 멱등이고(같은 원장 → 같은 결과, 임시 키 교체라 중간 상태가 없다) 현재
// 단일 인스턴스 전제다. 다중 인스턴스로 전환하면 임시 키 충돌·중복 부하를 재검토한다(plan.md 별도 절).
@Service
@RequiredArgsConstructor
@Slf4j
public class RankingRebuildService {

	private final TradeService tradeService;
	private final AccountService accountService;
	private final RankingStore rankingStore;

	// 기동 완료 시점 1회 재구성 — Redis가 비어 있는 채로 서비스가 뜨는 것을 막는다.
	// BithumbFeedLifecycle과 같은 훅이며, 주기 배치와 완전히 같은 rebuildAll() 경로를 탄다.
	@EventListener(ApplicationReadyEvent.class)
	public void rebuildOnStartup() {
		log.info("기동 시 랭킹 재구성 시작");
		rebuildAll();
	}

	// 매일 04:20(KST) 정기 재구성. 크론 값의 정본은 application.yml의 ranking.rebuild.cron이며 코드에 박지 않는다.
	// zone = "Asia/Seoul"을 반드시 함께 둔다 — 배포 JVM 기본 타임존이 UTC라 빠뜨리면 예외도 로그도 없이
	// KST 13:20에 돈다.
	@Scheduled(cron = "${ranking.rebuild.cron}", zone = "Asia/Seoul")
	public void rebuildOnSchedule() {
		log.info("정기 랭킹 재구성 시작");
		rebuildAll();
	}

	// 모든 시장을 각각 재구성한다. 시장마다 try/catch로 감싸 한 시장의 실패(DB 장애 등)가 다음 시장을 막지
	// 않게 한다 — replaceAll은 이미 예외를 삼키지만 그 앞의 DB 조회는 예외를 던질 수 있다.
	public void rebuildAll() {
		for (Market market : Market.values()) {
			try {
				rebuild(market);
			} catch (Exception e) {
				log.error("랭킹 재구성 실패. market={}", market, e);
			}
		}
	}

	// 대상 판정은 매도 체결 이력(trades.side = 'SELL')이고 score는 accounts.realized_pnl이다. 둘을 분리하는
	// 이유는 매도했지만 실현손익이 정확히 0인 계좌 때문이다 — realized_pnl != 0으로 대상을 고르면 그 계좌가
	// 빠져 재구성 결과가 유실 전과 달라진다(spec.md 비즈니스 규칙).
	public void rebuild(Market market) {
		List<Long> accountIds = tradeService.getSoldAccountIds(market);
		List<RankingEntryDto> entries = accountService.getAccountsByIds(accountIds)
			.stream()
			.map(account -> new RankingEntryDto(account.getId(), account.getRealizedPnl()))
			.toList();
		rankingStore.replaceAll(market, entries);
		log.info("랭킹 재구성 완료. market={}, 대상 계좌 수={}", market, entries.size());
	}
}
