package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialCandleResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PriceGuideRangeResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.TutorialPriceSeriesDto;
import com.finplay.api.domain.market.service.TutorialScenarioPriceGuideRangeCalculator;
import com.finplay.api.domain.market.service.TutorialScenarioScript;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptChartService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticeScenarioProgressService practiceScenarioProgressService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PracticeTutorialChartResponse getChart(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarket(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		LocalDateTime now = attempt.getStatus() == PracticeAttemptStatus.COMPLETED
			? attempt.getCompletedAt()
			: LocalDateTime.now(clock);
		return toResponse(attempt, now);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PracticeTutorialChartResponse tick(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		canonicalPriceService.publishedMinute(attempt, now);
		if (attempt.usesScenarioScript()) {
			practiceScenarioProgressService.advance(attempt, now);
		} else {
			practiceOrderSettlementService.settleCurrentRun(
				attempt.getId(), attempt.getRunNumber(), now, canonicalPriceService.canonicalPrice(attempt, now));
		}
		return toResponse(attempt, now);
	}

	private PracticeTutorialChartResponse toResponse(PracticeAttempt attempt, LocalDateTime now) {
		long publishedMinute = canonicalPriceService.publishedMinute(attempt, now);
		TutorialPriceSeriesDto series = canonicalPriceService.priceSeries(attempt, now);
		TutorialScenarioScript script = attempt.usesScenarioScript() ? canonicalPriceService.script(attempt) : null;
		PracticeScenarioNarrativeDto narrative = script != null
			? PracticeScenarioNarrativeCalculator.calculate(attempt, script)
			: PracticeScenarioNarrativeDto.EMPTY;
		return new PracticeTutorialChartResponse(
			attempt.getId(),
			attempt.getRunNumber(),
			attempt.getInstrument().getId(),
			attempt.getTutorialDate().atTime(12, 0).plusMinutes(publishedMinute),
			PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE,
			series.candles().stream().map(PracticeTutorialCandleResponse::from).toList(),
			narrative.scenarioStage(),
			narrative.scenarioProgressing(),
			narrative.causeStatus(),
			narrative.revealedEvents(),
			toPriceGuideRange(script));
	}

	private PriceGuideRangeResponse toPriceGuideRange(TutorialScenarioScript script) {
		if (script == null || !script.events().isEmpty()) {
			return null;
		}
		return TutorialScenarioPriceGuideRangeCalculator.calculate(script)
			.map(range -> new PriceGuideRangeResponse(range.low(), range.high()))
			.orElse(null);
	}
}
