package com.finplay.api.domain.order.listener;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.config.LimitOrderFillExecutorProperties;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderFillExecutorRouter;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import java.util.ArrayList;
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
	private final LimitOrderFillExecutorRouter limitOrderFillExecutorRouter;
	private final LimitOrderFillExecutorProperties limitOrderFillExecutorProperties;

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
			log.debug("코인 목록에 없는 심볼의 가격 틱을 무시합니다. symbol={}", event.symbol());
			return;
		}
		Long instrumentId = instrument.get().getId();

		List<Order> candidates = orderRepository.findPendingLimitOrdersToFill(instrumentId, event.price());
		if (limitOrderFillExecutorProperties.enabled()) {
			submitInBatches(instrumentId, candidates);
		} else {
			for (Order candidate : candidates) {
				fillOneCandidate(candidate.getId());
			}
		}
	}

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

	private void fillOneCandidate(Long orderId) {
		try {
			limitOrderFillService.fillIfPending(orderId);
		} catch (Exception e) {
			log.error("지정가 주문 체결 처리 중 예외 발생. orderId={}", orderId, e);
		}
	}

	private void fillBatch(List<Long> orderIds) {
		try {
			limitOrderFillService.fillBatch(orderIds);
		} catch (Exception e) {
			log.error("지정가 주문 배치 체결 처리 중 예외 발생. orderIds={}", orderIds, e);
		}
	}
}
