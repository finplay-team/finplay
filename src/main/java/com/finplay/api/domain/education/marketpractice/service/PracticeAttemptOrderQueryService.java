package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.service.OrderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptOrderQueryService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final OrderService orderService;

	@Transactional(readOnly = true)
	public List<OrderListItemResponse> getCurrentRunOrders(Long userId, Market market) {
		return practiceAttemptRepository.findByUserIdAndMarket(userId, market)
			.map(attempt -> orderService.getPracticeRunOrders(attempt.getId(), attempt.getRunNumber()))
			.orElseGet(List::of);
	}
}
