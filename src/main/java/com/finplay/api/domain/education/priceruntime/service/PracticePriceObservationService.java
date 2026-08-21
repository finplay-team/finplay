// holding 관찰이 가상 가격 세션의 currentPrice를 가격원으로 쓸지 판단하는 파사드
package com.finplay.api.domain.education.priceruntime.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ai/specs/030-coin-practice-price-runtime plan.md "holding 관찰 연결" 절(이슈 #321 3안)을 구현한다.
 * {@code buyTrade → order.practicePriceSessionId} 순서로 세션 귀속을 역추적해, 세션이 있으면 owner·instrument
 * 일치를 다시 검증하고 ACTIVE/COMPLETED 세션의 {@code currentPrice}를 반환한다. sessionId가 없으면
 * {@link Optional#empty()}를 반환해 marketpractice가 기존 {@code PriceQueryService} 경로로 fallback하게 한다.
 * chain이 이미 owner·instrument를 검증했으므로 sessionId가 있는데도 owner·instrument가 어긋나는 경우는 데이터
 * 이상 상태다 — 실제 가격으로 조용히 fallback하지 않고 409 {@code PRACTICE_EVIDENCE_MISSING}으로 거부한다.
 */
@Service
@RequiredArgsConstructor
public class PracticePriceObservationService {

	private final TradeService tradeService;
	private final PracticePriceSessionRepository practicePriceSessionRepository;

	@Transactional(readOnly = true)
	public Optional<BigDecimal> findObservationPrice(Long userId, Long buyTradeId, Long instrumentId) {
		Optional<Long> sessionId = tradeService.findPracticePriceSessionId(buyTradeId);
		if (sessionId.isEmpty()) {
			return Optional.empty();
		}

		PracticePriceSession session = practicePriceSessionRepository
			.findByIdAndUserId(sessionId.get(), userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		if (!session.getInstrumentId().equals(instrumentId)) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}

		return Optional.of(session.getCurrentPrice());
	}
}
