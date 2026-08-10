// 코인 튜토리얼 가상 가격 세션의 next-tick 진행만 담당하는 서비스(이슈 #319 코멘트 2안 — 세션 생성·조회 서비스와 분리)
package com.finplay.api.education.priceruntime.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.priceruntime.domain.PracticePriceSession;
import com.finplay.api.education.priceruntime.domain.PracticePriceSessionStatus;
import com.finplay.api.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.education.priceruntime.repository.PracticePriceSessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticePriceTickService {

	private final PracticePriceSessionRepository practicePriceSessionRepository;
	private final Clock clock;

	@Transactional
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
		session.advance(expectedTick, nextPrice, LocalDateTime.now(clock));

		return PracticePriceSessionResponse.from(session);
	}
}
