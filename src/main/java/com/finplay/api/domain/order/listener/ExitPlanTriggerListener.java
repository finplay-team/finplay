// 코인 가격 갱신 이벤트를 받아 트리거 조건을 충족한 OCO 손절·익절 예약을 찾아 건별로 체결을 위임하는 리스너
package com.finplay.api.domain.order.listener;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExitPlanTriggerListener {

	private final InstrumentService instrumentService;
	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanFillService exitPlanFillService;

	// 일반 리스너다(AFTER_COMMIT 아님) — 가격 수신 자체가 DB 트랜잭션이 아니므로 커밋 대기 대상이 없다
	// (LimitOrderTriggerListener와 동일 근거, 021 plan.md). 메서드 본문 전체를 try/catch로 감싸 어떤 예외도
	// 빗썸 피드 수신 스레드로 전파하지 않는다.
	// "유효 갱신 이벤트에서만 판정"은 CryptoPriceUpdatedEvent가 애초에 실제 연결된 틱에서만 발행되므로
	// 별도 FeedConnectionStatus 체크가 필요 없다(015-limit-order가 이미 이 전제로 설계했고
	// LimitOrderTriggerListener도 별도 체크를 하지 않는다).
	@EventListener
	public void onPriceUpdated(CryptoPriceUpdatedEvent event) {
		try {
			handle(event);
		} catch (Exception e) {
			log.error("OCO 손절·익절 트리거 처리 중 예외 발생. symbol={}", event.symbol(), e);
		}
	}

	private void handle(CryptoPriceUpdatedEvent event) {
		Optional<Instrument> instrument = instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, event.symbol());
		if (instrument.isEmpty()) {
			// 코인 목록에 없는 심볼일 수 있다(MKT-003과 동일하게 관용적으로 처리) — 예외 없이 종료.
			log.debug("코인 목록에 없는 심볼의 가격 틱을 무시합니다. symbol={}", event.symbol());
			return;
		}

		List<ExitPlan> candidates = exitPlanRepository
			.findPendingExitPlansToFill(instrument.get().getId(), event.price());
		for (ExitPlan candidate : candidates) {
			fillOneCandidate(candidate.getId(), event.price());
		}
	}

	// 후보 1건이 실패해도 나머지 후보 처리에 영향을 주지 않는다 — 각 호출은 ExitPlanFillService 안에서
	// 독립된 새 트랜잭션을 연다(이 리스너 메서드 자체는 트랜잭션이 아니다).
	private void fillOneCandidate(Long exitPlanId, BigDecimal currentPrice) {
		try {
			exitPlanFillService.fillIfPending(exitPlanId, currentPrice);
		} catch (Exception e) {
			log.error("OCO 손절·익절 예약 체결 처리 중 예외 발생. exitPlanId={}", exitPlanId, e);
		}
	}
}
