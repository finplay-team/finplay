package com.finplay.api.domain.education.priceruntime.service;

import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
