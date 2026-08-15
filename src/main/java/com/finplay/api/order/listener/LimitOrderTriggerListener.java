// 코인 가격 갱신 이벤트를 받아 체결 조건을 충족한 지정가 주문을 찾아 건별로 체결을 위임하는 리스너
package com.finplay.api.order.listener;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.config.LimitOrderFillExecutorProperties;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.service.LimitOrderFillExecutorRouter;
import com.finplay.api.order.service.LimitOrderFillService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <b>체결(fillBatch)은 피드 스레드에서 직접 돌지 않는다(ADR-0024).</b> 이 리스너는 후보 조회까지만 피드
 * 스레드에서 하고, 실제 체결은 {@link LimitOrderFillExecutorRouter}가 고르는 종목별 전용 스레드에 맡긴 뒤
 * 곧바로 반환한다. 그래서 한 가격 틱에 매칭되는 지정가가 아무리 많아도(뉴스발 급등이 아니라 그저 그 가격에
 * 지정가가 많이 쌓여 있는 것만으로도) 다음 가격 틱을 받는 스레드 자체가 막히지 않는다.
 *
 * <p>{@code order.limit-fill-executor.enabled=false}면 실행기를 거치지 않고 기존처럼 이 스레드에서 그대로
 * 순차 처리한다 — 비동기 경로에 문제가 생기면 재배포 없이 되돌릴 수단이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LimitOrderTriggerListener {

	private final InstrumentService instrumentService;
	private final OrderRepository orderRepository;
	private final LimitOrderFillService limitOrderFillService;
	private final LimitOrderFillExecutorRouter limitOrderFillExecutorRouter;
	private final LimitOrderFillExecutorProperties limitOrderFillExecutorProperties;

	// 일반 리스너다(AFTER_COMMIT 아님) — 가격 수신 자체가 DB 트랜잭션이 아니므로 커밋 대기 대상이 없다(spec.md).
	// 메서드 본문 전체를 try/catch로 감싸 어떤 예외도 빗썸 피드 수신 스레드로 전파하지 않는다
	// (RankingEventListener와 동일 관례). 이 catch는 candidates 조회 자체의 실패만 잡는다 — 체결
	// (fillOneCandidate/fillBatch) 쪽 예외는 실행기가 켜져 있으면 별도 스레드에서 나므로 거기서 각자 잡아 로깅한다.
	@EventListener
	public void onPriceUpdated(CryptoPriceUpdatedEvent event) {
		try {
			handle(event);
		} catch (Exception e) {
			log.error("지정가 체결 트리거 처리 중 예외 발생. symbol={}", event.symbol(), e);
		}
	}

	private void handle(CryptoPriceUpdatedEvent event) {
		Optional<Instrument> instrument = instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, event.symbol());
		if (instrument.isEmpty()) {
			// 코인 목록에 없는 심볼일 수 있다(MKT-003과 동일하게 관용적으로 처리) — 예외 없이 종료.
			log.debug("코인 목록에 없는 심볼의 가격 틱을 무시합니다. symbol={}", event.symbol());
			return;
		}
		Long instrumentId = instrument.get().getId();

		// idx_orders_limit_fill 인덱스로 단일 쿼리 조회 — 이미 requestedAt asc, id asc로 정렬돼 반환된다
		// (동시 체결 처리 단위, spec.md 확정된 설계 결정 5번). 이 순서 그대로 실행기에 제출해야
		// LimitOrderFillExecutorRouter의 종목별 파티션(FIFO 단일 스레드)에서도 순서가 보존된다.
		List<Order> candidates = orderRepository.findPendingLimitOrdersToFill(instrumentId, event.price());
		if (limitOrderFillExecutorProperties.enabled()) {
			submitInBatches(instrumentId, candidates);
		} else {
			for (Order candidate : candidates) {
				fillOneCandidate(candidate.getId());
			}
		}
	}

	// ADR-0025 — 후보를 order.limit-fill-executor.batch-size 단위로 잘라 청크마다 실행기에 제출 1건으로
	// 넘긴다. 청크 안의 순서는 candidates의 정렬(requestedAt asc, id asc)을 그대로 보존하며, 파티션 자체는
	// ADR-0024 §결정 1과 동일하게 instrumentId로만 갈린다(청크 단위 도입이 파티셔닝을 바꾸지 않는다).
	private void submitInBatches(Long instrumentId, List<Order> candidates) {
		int batchSize = limitOrderFillExecutorProperties.batchSize();
		for (int start = 0; start < candidates.size(); start += batchSize) {
			int end = Math.min(start + batchSize, candidates.size());
			List<Long> orderIds = new ArrayList<>(end - start);
			for (Order candidate : candidates.subList(start, end)) {
				orderIds.add(candidate.getId());
			}
			limitOrderFillExecutorRouter.submit(instrumentId, () -> fillBatch(orderIds));
		}
	}

	// order.limit-fill-executor.enabled=false일 때만 쓰는 폴백 경로 — 후보 1건이 실패해도 나머지 후보
	// 처리에 영향을 주지 않는다. 각 호출은 LimitOrderFillService 안에서 독립된 새 트랜잭션을 연다(이 리스너
	// 메서드 자체는 트랜잭션이 아니다).
	private void fillOneCandidate(Long orderId) {
		try {
			limitOrderFillService.fillIfPending(orderId);
		} catch (Exception e) {
			log.error("지정가 주문 체결 처리 중 예외 발생. orderId={}", orderId, e);
		}
	}

	// ADR-0025 — 청크 전체가 한 트랜잭션이므로(LimitOrderFillService.fillBatch) 한 건의 실패가 청크 전체를
	// 롤백시킨다. 이 catch는 그 롤백된 예외를 로깅만 하고 삼킨다 — 잡지 않으면 ThreadPoolTaskExecutor
	// 기본 설정상 로그 없이 조용히 파티션 워커 스레드만 종료되고(교체 스레드가 새로 생성됨) 원인 추적이
	// 불가능해진다. 실패한 청크의 주문들은 여전히 PENDING이라 다음 가격 틱이 다시 후보로 집어낸다(ADR-0024
	// §결정 2와 같은 재시도 철학).
	private void fillBatch(List<Long> orderIds) {
		try {
			limitOrderFillService.fillBatch(orderIds);
		} catch (Exception e) {
			log.error("지정가 주문 배치 체결 처리 중 예외 발생. orderIds={}", orderIds, e);
		}
	}
}
