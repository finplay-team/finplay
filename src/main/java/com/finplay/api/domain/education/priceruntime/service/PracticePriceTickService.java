package com.finplay.api.domain.education.priceruntime.service;

import com.finplay.api.domain.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.education.priceruntime.event.PracticePriceTickAdvancedEvent;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticePriceTickService {

	private final PracticePriceSessionRepository practicePriceSessionRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PracticePriceSessionResponse advanceTick(Long userId, Long sessionId, Integer expectedTick) {
		PracticePriceSession session = practicePriceSessionRepository
			.findByIdAndUserIdForUpdate(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		if (session.getStatus() == PracticePriceSessionStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED);
		}
		int expected = session.getCurrentTick() + 1;
		if (!expectedTick.equals(expected)) {
			throw new BusinessException(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT);
		}

		BigDecimal nextPrice = PracticePriceGeneratorV1.nextPrice(
			session.getSeed(), expectedTick, session.getCurrentPrice(), session.getStartPrice());
		boolean lastTick = expectedTick == PracticePriceSession.maxTick();
		session.advance(expectedTick, nextPrice);

		eventPublisher.publishEvent(new PracticePriceTickAdvancedEvent(
			session.getId(), session.getUserId(), session.getInstrumentId(), expectedTick, nextPrice, lastTick));

		if (lastTick) {
			session.complete(LocalDateTime.now(clock));
		}

		return PracticePriceSessionResponse.from(session);
	}
}
