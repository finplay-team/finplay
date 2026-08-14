// holdingId로 evidence chain을 재해석해 실습 3단계 가격 관찰 1건을 append-only로 저장하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.priceruntime.service.PracticePriceObservationService;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/specs/026-market-order-practice-tutorial plan.md "확정 HTTP·JSON 계약" 절의
 * {@code POST /api/education/practice/holding-observations} 처리 순서를 구현한다. 관찰 저장은 append-only
 * insert라 비관적 락이 필요 없다(plan.md "트랜잭션과 경합" 절).
 */
@Service
@RequiredArgsConstructor
public class PracticeHoldingObservationService {

	private final HoldingService holdingService;
	private final MarketPracticeChainResolutionService chainResolutionService;
	private final PriceQueryService priceQueryService;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticePriceObservationService practicePriceObservationService;
	private final ReferencePriceCalculator referencePriceCalculator;
	private final EvidenceJudgmentService evidenceJudgmentService;
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final Clock clock;

	@Transactional
	public PracticeHoldingObservationResponse createObservation(
		Long userId, PracticeHoldingObservationCreateRequest request) {
		Holding holding = holdingService.findHoldingForOwner(userId, request.holdingId())
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		String tutorialKey = resolveTutorialKey(holding.getInstrument().getMarket());
		// resolve()는 tutorialKey 안에서 우선순위가 가장 높은 chain 1건만 고르므로, 사용자가 같은 market에서
		// 종목을 여러 개 완결했을 때 요청받은 holding과 다른 종목이 뽑힐 수 있다. holding이 속한 instrument로
		// 범위를 좁힌 resolveForInstrument를 써서 그 holding 자신의 chain만 재해석한다(PR #300 리뷰 반영).
		ResolvedPracticeChainDto chain = chainResolutionService
			.resolveForInstrument(userId, tutorialKey, holding.getInstrument().getId())
			.filter(resolved -> resolved.holdingId().equals(holding.getId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		ReferencePriceLines referenceLines = referencePriceCalculator.calculate(chain)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		// 세션 귀속 buyTrade(030 교육 지정가 체결)는 같은 세션 currentPrice를, 세션 없는 기존 시장가·실제
		// 지정가 buyTrade는 PriceQueryService.getPrice(표시 경로)를 쓴다 — 코인이 연결 유지 + stale이어도
		// 마지막 실제 가격을 그대로 관찰 근거로 받아들인다(의도적 승계, 032 PRICE-STALE-005, 이슈 #355).
		// 가격이 아예 없으면(연결 끊김·미수신) getPrice가 스스로 PRICE_UNAVAILABLE(409)을 던진다
		// (이슈 #321, plan.md "holding 관찰 연결").
		LocalDateTime observedAt = LocalDateTime.now(clock);
		BigDecimal observedPrice = holding.getInstrument().isTutorialSample()
			? canonicalPriceService.canonicalPriceForMutation(userId, holding.getInstrument(), observedAt)
			: practicePriceObservationService
				.findObservationPrice(userId, chain.buyTradeId(), holding.getInstrument().getId())
				.orElseGet(() -> priceQueryService.getPrice(holding.getInstrument().getId()).price());

		List<PracticeMarketObservation> existingObservations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(userId, holding.getId());
		ObservationEvidenceJudgment judgment = evidenceJudgmentService.judgeObservationEvidence(
			chain.buyTradeEntryPrice(),
			referenceLines.referenceStopLossPrice(),
			referenceLines.referenceTakeProfitPrice(),
			observedPrice,
			existingObservations,
			observedAt);

		PracticeMarketObservation observation = PracticeMarketObservation.create(
			userId,
			holding,
			holding.getInstrument().getId(),
			observedPrice,
			judgment.closerToBoundary(),
			judgment.closerBoundary(),
			judgment.evidenceType(),
			observedAt);

		return PracticeHoldingObservationResponse.from(practiceMarketObservationRepository.save(observation));
	}

	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> PracticeIntentionService.TUTORIAL_KEY;
			case CRYPTO -> PracticeIntentionService.COIN_TUTORIAL_KEY;
		};
	}
}
