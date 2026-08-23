// 매도 이력 원장(trades)과 계좌 실현손익(accounts)에서 랭킹 ZSET을 통째로 재구성하는 서비스 (이슈 #279)
package com.finplay.api.domain.ranking.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.ranking.store.RankingEntryDto;
import com.finplay.api.domain.ranking.store.RankingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
// 시장 단위 분산 락을 둔다(RankingRebuildLock, 이슈 #539) — ADR-0021 §5(전환은 파이프라인이 상태를 읽어서
// 정한다)·§6(헬스체크 실패 시 이전 색으로 되돌린다)이 함께 확정한 블루-그린 배포는 전환 이후에도 이전 색
// 인스턴스를 다음 배포까지 내리지 않아, 배포와 배포 사이 대부분의 시간 동안 두 인스턴스가 같은 Redis를 보며
// 함께 떠 있다. 재구성이 멱등이라는 것(같은 원장 → 같은 결과)은 그대로지만, 임시 키
// ranking:{market}:rebuild가 인스턴스별로 분리돼 있지 않아 두 인스턴스가 같은 시장을 동시에 재구성하면 한쪽의
// DEL이 다른 쪽이 ZADD로 채우던 내용을 지울 수 있다 — 그 상태로 먼저 끝난 쪽이 RENAME하면 매도 이력이 있는
// 계좌 일부가 랭킹에서 빠진 채로 서비스되고, 다음 재구성(최대 24시간 뒤)까지 알아챌 방법이 없다.
@Service
@RequiredArgsConstructor
@Slf4j
public class RankingRebuildService {

	private final TradeService tradeService;
	private final AccountService accountService;
	private final RankingStore rankingStore;
	private final RankingRebuildLock rankingRebuildLock;

	// 기동 완료 시점 1회 재구성 — Redis가 비어 있는 채로 서비스가 뜨는 것을 막는다.
	// BithumbFeedLifecycle과 같은 훅이며, 주기 배치와 완전히 같은 rebuildAll() 경로를 탄다.
	//
	// 이 훅이 걸리는 만큼 readiness(ACCEPTING_TRAFFIC)도 늦어진다 — 그리고 @Order로는 그걸 못 앞당긴다.
	// 한때 @Order(LOWEST_PRECEDENCE)를 붙였다가 걷어낸 이유를 남긴다(PR #284 리뷰에서 바이트코드로 확인).
	//   1) readiness는 ready 리스너 중 하나가 아니다. EventPublishingRunListener.ready()가
	//      publishEvent(ApplicationReadyEvent)로 리스너를 전부 동기 디스패치한 "다음" 줄에서
	//      AvailabilityChangeEvent.publish(ACCEPTING_TRAFFIC)를 부른다. 리스너끼리 순서를 바꿔도
	//      readiness는 항상 전원이 끝난 뒤에 나간다.
	//   2) 애초에 값이 기본값과 같다. ApplicationListenerMethodAdapter.resolveOrder는 @Order가 없으면
	//      LOWEST_PRECEDENCE를 돌려주므로, 붙여도 다른 ready 리스너와의 상대 순서조차 바뀌지 않는다.
	// 지연을 실제로 없애려면 리스너 안에서 별도 스레드로 넘겨야 하는데, RANK-001 plan.md가 "별도 스레드풀·
	// @EnableAsync를 추가하지 않는다"고 못박아 둔 결정이라 이번 범위에서 뒤집지 않았다. 남은 지연은 spec.md
	// "알려진 한계"에 적었다. 동기라서 이 메서드가 반환하는 시점이 곧 "기동 시 재구성이 끝난 시점"이고,
	// 기동 로그만으로 완료 여부를 판정할 수 있다는 점은 그대로다.
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
	//
	// 계좌 배치 조회를 RankingStore.REBUILD_CHUNK_SIZE로 나눠 부른다(PR #284 리뷰). ZADD만 청크로 나누고 그
	// 앞의 IN 절 조회를 통째로 두면 계좌 수가 늘 때 IN 절 파라미터·패킷 쪽이 먼저 한계에 닿는데, 그 예외는
	// rebuildAll의 시장별 catch에 삼켜져 로그로만 남는다. 청크를 여기서 나누는 이유는 AccountService의
	// getAccountsByIds가 랭킹 전용 메서드가 아니기 때문이다 — 랭킹 재구성의 배치 크기를 그 안에 넣으면
	// account 도메인이 ranking의 상수에 의존하게 되고(ADR-0002 도메인 경계 역행), 다른 호출자에게도 이
	// 사정이 딸려 간다. 반대로 여기서 나누면 두 청크 크기의 정본이 RankingStore 한 곳으로 남는다.
	public void rebuild(Market market) {
		Optional<String> lockToken = rankingRebuildLock.tryLock(market);
		if (lockToken.isEmpty()) {
			log.info("랭킹 재구성 락을 얻지 못해 이번 실행을 건너뜁니다(다른 인스턴스가 이미 처리 중) - market={}", market);
			return;
		}
		try {
			List<Long> accountIds = tradeService.getSoldAccountIds(market);
			List<RankingEntryDto> entries = new ArrayList<>(accountIds.size());
			for (int start = 0; start < accountIds.size(); start += RankingStore.REBUILD_CHUNK_SIZE) {
				int end = Math.min(start + RankingStore.REBUILD_CHUNK_SIZE, accountIds.size());
				for (Account account : accountService.getAccountsByIds(accountIds.subList(start, end))) {
					entries.add(new RankingEntryDto(account.getId(), account.getRealizedPnl()));
				}
			}
			// replaceAll은 예외를 삼키므로 반환값이 유일한 성공 판정 근거다. 실패한 tick에서 "완료" INFO를 남기면
			// 로그만 보는 사람이 실패를 성공으로 읽는다(PR #284 QA 지적). 실패 사유·스택트레이스는 replaceAll이
			// ERROR로 이미 남기므로 여기서 다시 찍지 않는다.
			if (rankingStore.replaceAll(market, entries)) {
				log.info("랭킹 재구성 완료. market={}, 대상 계좌 수={}", market, entries.size());
			}
		} finally {
			rankingRebuildLock.unlock(market, lockToken.get());
		}
	}
}
