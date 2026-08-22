// 코인 튜토리얼 가상 가격 세션의 next-tick 진행만 담당하는 서비스(이슈 #319 코멘트 2안 — 세션 생성·조회 서비스와 분리)
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

	// tick·가격 갱신(advance) → 세션 전용 이벤트 발행(같은 트랜잭션 안에서 PracticeTickFillListener가 동기 처리,
	// 교육 지정가 체결·마지막 tick 취소·예약 반환) → tick 99면 세션 완료(complete) 순서를 지킨다(plan.md).
	// ADR-0028 §후속 — 이 트랜잭션 안에서 PracticeTickFillListener → PracticeOrderSettlementService.settleOnTick
	// → LimitOrderFillService.fillIfPending이 동기 호출된다. fillIfPending 자신의 격리수준 선언은 이미 열린
	// 이 트랜잭션에 합류(REQUIRED)할 때 Spring이 조용히 무시하므로, holdings INSERT 데드락 완화가 실제로
	// 적용되려면 이 트랜잭션을 여는 지점에 직접 READ COMMITTED를 명시해야 한다.
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
