// holdingId로 evidence chain·A/B 관찰을 재검증해 실습 3단계 자유 복기를 저장하고 튜토리얼 완료를 확정하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeProgress;
import com.finplay.api.education.domain.PracticeProgressStatus;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Market;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/specs/026-market-order-practice-tutorial plan.md "확정 HTTP·JSON 계약" 절의 {@code POST
 * /api/education/practice/holding-reflections} 처리 순서를 구현한다. {@code practice_progresses}를 잠근 뒤
 * evidence를 재검증하고, 복기·완료 저장과 progress 전이를 같은 트랜잭션에서 처리한다(plan.md "트랜잭션과 경합").
 */
@Service
@RequiredArgsConstructor
public class PracticeHoldingReflectionService {

	private static final short PROMPT_VERSION = 1;

	private final HoldingService holdingService;
	private final MarketPracticeChainResolutionService chainResolutionService;
	private final PracticeProgressRepository practiceProgressRepository;
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final PracticeMarketReflectionRepository practiceMarketReflectionRepository;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final Clock clock;

	@Transactional
	public PracticeHoldingReflectionResponse createReflection(
		Long userId, PracticeHoldingReflectionCreateRequest request) {
		Holding holding = holdingService.findHoldingForOwner(userId, request.holdingId())
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		String tutorialKey = resolveTutorialKey(holding.getInstrument().getMarket());

		// practice_progresses(DB)를 먼저 잠근다 — 사전 의도 기록 시점에 이미 만들어진 행만 잠그며, 이 이슈에서
		// 새로 만들지 않는다(plan.md "트랜잭션과 경합", 지시사항). 행이 없으면 의도 기록을 거치지 않고 접근한
		// 것이므로 완료를 걸 진행 상태 자체가 없다 — 409 PRACTICE_EVIDENCE_MISSING.
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		if (progress.getStatus() == PracticeProgressStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}

		ResolvedPracticeChainDto chain = chainResolutionService
			.resolveForInstrument(userId, tutorialKey, holding.getInstrument().getId())
			.filter(resolved -> resolved.holdingId().equals(holding.getId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		List<PracticeMarketObservation> observations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(userId, holding.getId());
		boolean hasEvidence = observations.stream()
			.anyMatch(observation -> observation.getEvidenceType() != null);
		if (!hasEvidence) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		PracticeMarketReflection reflection = practiceMarketReflectionRepository.save(
			PracticeMarketReflection.create(userId, holding, tutorialKey, PROMPT_VERSION, request.answer(), now));

		practiceCompletionRepository.save(PracticeCompletion.create(userId, tutorialKey, reflection, now));
		progress.complete(now);

		return PracticeHoldingReflectionResponse.from(reflection);
	}

	// PracticeHoldingObservationService.resolveTutorialKey와 동일 패턴(지시사항)
	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> PracticeIntentionService.TUTORIAL_KEY;
			case CRYPTO -> PracticeIntentionService.COIN_TUTORIAL_KEY;
		};
	}
}
