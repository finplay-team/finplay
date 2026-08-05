// 코인 가격 갱신 이벤트를 받아 체결 조건을 충족한 지정가 주문을 찾아 건별로 체결을 위임하는 리스너
package com.finplay.api.order.listener;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.service.LimitOrderFillService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LimitOrderTriggerListener {

	private final InstrumentService instrumentService;
	private final OrderRepository orderRepository;
	private final LimitOrderFillService limitOrderFillService;

	// 일반 리스너다(AFTER_COMMIT 아님) — 가격 수신 자체가 DB 트랜잭션이 아니므로 커밋 대기 대상이 없다(spec.md).
	// 메서드 본문 전체를 try/catch로 감싸 어떤 예외도 빗썸 피드 수신 스레드로 전파하지 않는다
	// (RankingEventListener와 동일 관례).
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

		// idx_orders_limit_fill 인덱스로 단일 쿼리 조회 — 이미 requestedAt asc, id asc로 정렬돼 반환된다
		// (동시 체결 처리 단위, spec.md 확정된 설계 결정 5번).
		List<Order> candidates = orderRepository
			.findPendingLimitOrdersToFill(instrument.get().getId(), event.price());
		for (Order candidate : candidates) {
			fillOneCandidate(candidate.getId());
		}
	}

	// 후보 1건이 실패해도 나머지 후보 처리에 영향을 주지 않는다 — 각 호출은 LimitOrderFillService 안에서
	// 독립된 새 트랜잭션을 연다(이 리스너 메서드 자체는 트랜잭션이 아니다).
	private void fillOneCandidate(Long orderId) {
		try {
			limitOrderFillService.fillIfPending(orderId);
		} catch (Exception e) {
			log.error("지정가 주문 체결 처리 중 예외 발생. orderId={}", orderId, e);
		}
	}
}
