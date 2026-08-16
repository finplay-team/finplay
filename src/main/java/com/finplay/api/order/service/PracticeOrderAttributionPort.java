// order 도메인이 튜토리얼 attempt 잠금·최초 BUY snapshot을 요청하는 애플리케이션 포트
package com.finplay.api.order.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.Trade;
import java.time.LocalDateTime;
import java.util.Optional;

public interface PracticeOrderAttributionPort {

	Optional<PracticeOrderAttributionDto> lockForOrder(Long userId, Instrument instrument);

	PracticeOrderFillContextDto lockForFill(
		PracticeOrderFillAttributionDto attribution, LocalDateTime pricedAt);

	void createFirstBuyRiskSnapshot(Order order, Trade trade, LocalDateTime createdAt);
}
