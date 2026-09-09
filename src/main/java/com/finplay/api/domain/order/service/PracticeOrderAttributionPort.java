package com.finplay.api.domain.order.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import java.time.LocalDateTime;
import java.util.Optional;

public interface PracticeOrderAttributionPort {

	Optional<PracticeOrderAttributionDto> lockForOrder(Long userId, Instrument instrument, OrderType orderType);

	PracticeOrderFillContextDto lockForFill(
		PracticeOrderFillAttributionDto attribution, LocalDateTime pricedAt);

	void createRiskSnapshotOnBuyFill(Order order, Trade trade, LocalDateTime createdAt);

	boolean managesAutomaticExitPlans(Long practiceAttemptId, Long practiceAttemptRunNumber);

}
