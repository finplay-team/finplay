// 현재 attempt의 읽기 전용 29+1 차트와 명시적 canonical 지정가 tick을 처리하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialCandleResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.TutorialPriceSeriesDto;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptChartService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PracticeTutorialChartResponse getChart(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarket(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		LocalDateTime now = LocalDateTime.now(clock);
		return toResponse(attempt, now);
	}

	@Transactional
	public PracticeTutorialChartResponse tick(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		canonicalPriceService.publishedMinute(attempt, now);
		practiceOrderSettlementService.settleCurrentRun(attempt.getId(), attempt.getRunNumber(), now);
		return toResponse(attempt, now);
	}

	private PracticeTutorialChartResponse toResponse(PracticeAttempt attempt, LocalDateTime now) {
		long publishedMinute = canonicalPriceService.publishedMinute(attempt, now);
		TutorialPriceSeriesDto series = canonicalPriceService.priceSeries(attempt, now);
		return new PracticeTutorialChartResponse(
			attempt.getId(),
			attempt.getRunNumber(),
			attempt.getInstrument().getId(),
			attempt.getTutorialDate().atTime(12, 0).plusMinutes(publishedMinute),
			PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE,
			series.candles().stream().map(PracticeTutorialCandleResponse::from).toList());
	}
}
