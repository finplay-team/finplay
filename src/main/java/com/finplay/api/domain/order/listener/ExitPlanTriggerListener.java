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
			log.debug("코인 목록에 없는 심볼의 가격 틱을 무시합니다. symbol={}", event.symbol());
			return;
		}

		List<ExitPlan> candidates = exitPlanRepository
			.findPendingExitPlansToFill(instrument.get().getId(), event.price());
		for (ExitPlan candidate : candidates) {
			fillOneCandidate(candidate.getId(), event.price());
		}
	}

	private void fillOneCandidate(Long exitPlanId, BigDecimal currentPrice) {
		try {
			exitPlanFillService.fillIfPending(exitPlanId, currentPrice);
		} catch (Exception e) {
			log.error("OCO 손절·익절 예약 체결 처리 중 예외 발생. exitPlanId={}", exitPlanId, e);
		}
	}
}
