package com.finplay.api.domain.education.priceruntime.service;

import com.finplay.api.domain.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.service.PracticeLimitOrderCreationService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeLimitOrderService {

	private final PracticePriceSessionRepository practicePriceSessionRepository;
	private final PracticeLimitOrderCreationService practiceLimitOrderCreationService;

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
