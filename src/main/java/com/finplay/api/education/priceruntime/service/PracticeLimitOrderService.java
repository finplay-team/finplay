// 코인 가상 가격 세션에 귀속된 교육 전용 지정가 BUY 주문 생성을 담당하는 서비스
package com.finplay.api.education.priceruntime.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.priceruntime.domain.PracticePriceSession;
import com.finplay.api.education.priceruntime.domain.PracticePriceSessionStatus;
import com.finplay.api.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
import com.finplay.api.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.service.PracticeLimitOrderCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeLimitOrderService {

	private final PracticePriceSessionRepository practicePriceSessionRepository;
	private final PracticeLimitOrderCreationService practiceLimitOrderCreationService;

	// 세션을 owner 스코프로 먼저 잠근 뒤(잠금 순서 session → account → order insert, plan.md) order의
	// 공개 서비스에 생성을 위임한다. OrderRepository·AccountRepository를 이 도메인이 직접 주입하지 않는다(ADR-0002).
	@Transactional
	public LimitOrderResponse createOrder(Long userId, PracticeLimitOrderCreateRequest request) {
		PracticePriceSession session = practicePriceSessionRepository
			.findByIdAndUserIdForUpdate(request.practicePriceSessionId(), userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		if (session.getStatus() != PracticePriceSessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED);
		}
		if (!session.getInstrumentId().equals(request.instrumentId())) {
			throw new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_MISMATCH);
		}

		return practiceLimitOrderCreationService.createSessionBuyOrder(
			userId, session.getId(), request.instrumentId(), request.quantity(), request.limitPrice());
	}
}
