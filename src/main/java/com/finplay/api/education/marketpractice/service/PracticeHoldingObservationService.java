// holdingId로 evidence chain을 재해석해 실습 3단계 가격 관찰 1건을 append-only로 저장하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingService;
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
		ResolvedPracticeChainDto chain = chainResolutionService.resolve(userId, tutorialKey)
			.filter(resolved -> resolved.holdingId().equals(holding.getId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		ReferencePriceLines referenceLines = referencePriceCalculator.calculate(chain)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		// PriceQueryService.getPrice는 가격이 없으면 스스로 PRICE_UNAVAILABLE(409)을 던진다 (market 도메인 계약).
		PriceQuoteDto priceQuote = priceQueryService.getPrice(holding.getInstrument().getId());

		List<PracticeMarketObservation> existingObservations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(userId, holding.getId());
		LocalDateTime observedAt = LocalDateTime.now(clock);

		ObservationEvidenceJudgment judgment = evidenceJudgmentService.judgeObservationEvidence(
			chain.buyTradeEntryPrice(),
			referenceLines.referenceStopLossPrice(),
			referenceLines.referenceTakeProfitPrice(),
			priceQuote.price(),
			existingObservations,
			observedAt);

		PracticeMarketObservation observation = PracticeMarketObservation.create(
			userId,
			holding,
			holding.getInstrument().getId(),
			priceQuote.price(),
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
