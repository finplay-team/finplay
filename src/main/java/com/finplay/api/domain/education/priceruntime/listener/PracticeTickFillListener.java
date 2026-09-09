package com.finplay.api.domain.education.priceruntime.listener;

import com.finplay.api.domain.education.priceruntime.event.PracticePriceTickAdvancedEvent;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PracticeTickFillListener {

	private final PracticeOrderSettlementService practiceOrderSettlementService;

	@EventListener
	public void onTickAdvanced(PracticePriceTickAdvancedEvent event) {
		practiceOrderSettlementService.settleOnTick(event.sessionId(), event.price(), event.lastTick());
	}
}
