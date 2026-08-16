// 완료 attempt replay와 미완료 attempt의 원자적 실행 세대 재시작을 조정하는 교육 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.service.PracticeRunRestartCommand;
import com.finplay.api.order.service.PracticeRunRestartOrderService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptRestartService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeRunRestartOrderService practiceRunRestartOrderService;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final Clock clock;

	@Transactional
	public PracticeAttemptResponse restart(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			return toResponse(attempt);
		}

		LocalDateTime restartedAt = LocalDateTime.now(clock);
		practiceRunRestartOrderService.cleanupCurrentRun(new PracticeRunRestartCommand(
			attempt.getId(), attempt.getRunNumber(), userId, market,
			attempt.getInstrument() == null ? null : attempt.getInstrument().getId(),
			attempt.getInstrument() == null ? null : canonicalPriceService.canonicalPrice(attempt, restartedAt),
			restartedAt));
		attempt.restart(restartedAt);
		return toResponse(attempt);
	}

	private PracticeAttemptResponse toResponse(PracticeAttempt attempt) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(attempt, snapshot);
	}
}
