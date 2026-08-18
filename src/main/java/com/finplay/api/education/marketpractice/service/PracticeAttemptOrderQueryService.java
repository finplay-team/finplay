// 인증 사용자의 현재 튜토리얼 attempt·run에 귀속된 주문 목록을 조회하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.service.OrderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptOrderQueryService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final OrderService orderService;

	// 043: attempt가 없으면 오류가 아니라 빈 목록을 반환한다(spec TUTORIAL-ORDER-001).
	@Transactional(readOnly = true)
	public List<OrderListItemResponse> getCurrentRunOrders(Long userId, Market market) {
		return practiceAttemptRepository.findByUserIdAndMarket(userId, market)
			.map(attempt -> orderService.getPracticeRunOrders(attempt.getId(), attempt.getRunNumber()))
			.orElseGet(List::of);
	}
}
