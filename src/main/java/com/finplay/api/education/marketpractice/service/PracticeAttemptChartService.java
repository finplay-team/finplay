// 현재 attempt의 읽기 전용 29+1 차트와 명시적 canonical 지정가 tick을 처리하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialCandleResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.education.marketpractice.dto.response.PriceGuideRangeResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.TutorialPriceSeriesDto;
import com.finplay.api.market.service.TutorialScenarioPriceGuideRangeCalculator;
import com.finplay.api.market.service.TutorialScenarioScript;
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

	@Transactional
	public PracticeTutorialChartResponse tick(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		// 선택 상태 검증을 겸한다 — 종목 미선택 attempt는 여기서 PRACTICE_STEP_LOCKED로 걸린다.
		canonicalPriceService.publishedMinute(attempt, now);
		if (attempt.usesScenarioScript()) {
			// 생성기 버전 2는 진행 계산이 커서를 밀면서 건너뛴 가상 분마다 정산한다(SCENARIO-013). 여기서
			// settleCurrentRun을 한 번 더 부르면 같은 tick의 마지막 분이 두 번 판정된다.
			practiceScenarioProgressService.advance(attempt, now);
		} else {
			// 생성기 버전 1도 같은 시점 canonical 가격으로 OCO를 함께 판정한다 — 042가 예약을 CRYPTO에서만
			// 만들지만, 정산 진입점을 버전으로 갈라 두면 나중에 STOCK 대본이 들어올 때 한쪽만 고치게 된다.
			practiceOrderSettlementService.settleCurrentRun(
				attempt.getId(), attempt.getRunNumber(), now, canonicalPriceService.canonicalPrice(attempt, now));
		}
		return toResponse(attempt, now);
	}

	private PracticeTutorialChartResponse toResponse(PracticeAttempt attempt, LocalDateTime now) {
		long publishedMinute = canonicalPriceService.publishedMinute(attempt, now);
		TutorialPriceSeriesDto series = canonicalPriceService.priceSeries(attempt, now);
		// 041 6번 — 대본을 쓰지 않는 attempt는 네 필드가 비어 나간다. 조회 시점에 커서를 읽기만 하므로
		// GET chart의 "부수효과 없음" 계약(SCENARIO-022)은 그대로다.
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

	// 049 ORDERBASICS-011 — 판정식은 script.events().isEmpty() 하나다. 사건이 있는 대본(041)이거나
	// 대본을 쓰지 않는 attempt(script == null)면 null이다.
	private PriceGuideRangeResponse toPriceGuideRange(TutorialScenarioScript script) {
		if (script == null || !script.events().isEmpty()) {
			return null;
		}
		return TutorialScenarioPriceGuideRangeCalculator.calculate(script)
			.map(range -> new PriceGuideRangeResponse(range.low(), range.high()))
			.orElse(null);
	}
}
