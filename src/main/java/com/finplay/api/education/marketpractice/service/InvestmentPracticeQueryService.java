// 실제 favorite·intention·buyTrade·holding·관찰·복기 리소스를 조회해 3단계 실습 진행 상태를 계산하는 순수 조회 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Market;
import com.finplay.api.portfolio.domain.Holding;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/specs/026-market-order-practice-tutorial 이슈 #305 "GET /api/education/practice" 완료 조건의 상태
 * 계산표(1~5)를 그대로 구현한다. 어떤 것도 쓰지 않는 순수 조회이며, 완료 이후에는 {@code practice_completions}→
 * {@code practice_market_reflections}가 가리키는 holding의 chain·qualifying observation을 다시 조회할 뿐
 * favorite·intention(인메모리)이 유실돼도 completion 판정 자체는 흔들리지 않는다(spec.md "재시작 유실과 완료
 * 불변").
 */
@Service
@RequiredArgsConstructor
public class InvestmentPracticeQueryService {

	private static final String STATUS_COMPLETED = "COMPLETED";
	private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
	private static final String STATUS_NOT_STARTED = "NOT_STARTED";
	// 샘플 종목 chain(4단계)에서만 등장하는 상태 — 매수 후 5분 이내에 매도 evidence가 없으면 만료된다
	// (plan.md "3. 매도 단계 API 설계" GET 4단계 응답 표).
	private static final String STATUS_EXPIRED = "EXPIRED";
	// 4단계가 STATUS_NOT_STARTED(잠긴 미착수)를 재사용하면 locked=false와 조합될 때 "지금 매도해야 하는
	// 실행 가능한 단계"를 "아직 진입 전"으로 오인시켜 프론트엔드가 CTA를 숨길 위험이 있다(tester 지적,
	// PR #340 이후 정정). 매도 대기(잠기지 않음, 5분 이내)만을 가리키는 별도 상태값을 쓴다.
	private static final String STATUS_AWAITING_SALE = "AWAITING_SALE";
	private static final long SALE_DEADLINE_MINUTES = 5;

	private final FavoriteService favoriteService;
	private final MarketPracticeChainResolutionService chainResolutionService;
	private final ReferencePriceCalculator referencePriceCalculator;
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public InvestmentPracticeResponse getProgress(Long userId, Market market) {
		String tutorialKey = resolveTutorialKey(market);

		Optional<PracticeCompletion> completion = practiceCompletionRepository
			.findByUserIdAndTutorialKey(userId, tutorialKey);
		if (completion.isPresent()) {
			return buildCompletedResponse(userId, tutorialKey, completion.get());
		}

		Optional<ResolvedPracticeChainDto> chain = chainResolutionService.resolve(userId, tutorialKey);
		if (chain.isPresent()) {
			return buildChainResponse(userId, tutorialKey, chain.get());
		}

		List<FavoriteResponse> marketFavorites = favoriteService.getFavorites(userId).content().stream()
			.filter(favorite -> market.name().equals(favorite.market()))
			.toList();
		if (!marketFavorites.isEmpty()) {
			return buildFavoriteOnlyResponse(tutorialKey, marketFavorites);
		}

		return buildNotStartedResponse(tutorialKey);
	}

	// 완료 조건 1: practice_completions 행이 있으면 COMPLETED, 1·2·3단계 전부 COMPLETED. evidence는
	// completion -> reflection -> holding 관계에서 chain·qualifying observation을 재조회해 채우되, favorite·
	// intention이 유실됐으면(재시작) 해당 필드만 null로 남긴다(id·시각 쌍 규칙만 유지, 지시사항).
	private InvestmentPracticeResponse buildCompletedResponse(
		Long userId, String tutorialKey, PracticeCompletion completion) {
		PracticeMarketReflection reflection = completion.getReflection();
		Holding holding = reflection.getHolding();
		boolean sampleInstrument = holding.getInstrument().isTutorialSample();

		Optional<ResolvedPracticeChainDto> chain = chainResolutionService
			.resolveForInstrument(userId, tutorialKey, holding.getInstrument().getId())
			.filter(resolved -> resolved.holdingId().equals(holding.getId()));

		List<PracticeMarketObservation> observations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(userId, holding.getId());
		Optional<PracticeMarketObservation> qualifyingObservation = observations.stream()
			.filter(observation -> observation.getEvidenceType() != null)
			.findFirst();

		PracticeEvidenceResponse evidence = new PracticeEvidenceResponse(
			chain.map(ResolvedPracticeChainDto::favoriteId).orElse(null),
			chain.map(ResolvedPracticeChainDto::favoriteCreatedAt).orElse(null),
			chain.map(ResolvedPracticeChainDto::intentionId).orElse(null),
			chain.map(ResolvedPracticeChainDto::intentionCreatedAt).orElse(null),
			chain.map(ResolvedPracticeChainDto::buyTradeId).orElse(null),
			chain.map(ResolvedPracticeChainDto::buyTradeExecutedAt).orElse(null),
			holding.getId(),
			null,
			null,
			qualifyingObservation.map(PracticeMarketObservation::getId).orElse(null),
			qualifyingObservation.map(PracticeMarketObservation::getObservedAt).orElse(null),
			qualifyingObservation.map(observation -> observation.getEvidenceType().name()).orElse(null),
			reflection.getId(),
			reflection.getCreatedAt(),
			null,
			null,
			null);

		if (!sampleInstrument) {
			List<PracticeStepResponse> steps = List.of(
				new PracticeStepResponse(1, STATUS_COMPLETED, false, evidence),
				new PracticeStepResponse(2, STATUS_COMPLETED, false, evidence),
				new PracticeStepResponse(3, STATUS_COMPLETED, false, evidence));
			return new InvestmentPracticeResponse(
				tutorialKey, STATUS_COMPLETED, null, steps, completion.getCompletedAt());
		}

		// 샘플 종목 chain은 4단계(매도·복기)까지 완료돼야 practice_completions가 생기므로(4단계
		// evidence(a)·(b) 모두 필요), 완료 응답도 4단계로 확장해 매도 evidence를 노출한다.
		PracticeEvidenceResponse stepFourEvidence = new PracticeEvidenceResponse(
			evidence.favoriteId(), evidence.favoriteCreatedAt(), evidence.intentionId(), evidence.intentionCreatedAt(),
			evidence.buyTradeId(), evidence.buyTradeExecutedAt(), evidence.holdingId(),
			evidence.referenceStopLossPrice(), evidence.referenceTakeProfitPrice(),
			evidence.observationId(), evidence.observationObservedAt(), evidence.evidenceType(),
			evidence.reflectionId(), evidence.reflectionCreatedAt(),
			chain.map(ResolvedPracticeChainDto::sellTradeId).orElse(null),
			chain.map(ResolvedPracticeChainDto::sellTradeExecutedAt).orElse(null),
			evidence.buyTradeExecutedAt() == null
				? null
				: evidence.buyTradeExecutedAt().plusMinutes(SALE_DEADLINE_MINUTES));

		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(2, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(3, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(4, STATUS_COMPLETED, false, stepFourEvidence));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_COMPLETED, null, steps, completion.getCompletedAt());
	}

	// 완료 조건 2·3: 완료되지 않았지만 유효 chain이 있으면 1·2단계는 COMPLETED, 3단계는 IN_PROGRESS다. chain에
	// qualifying observation이 있으면(조건 2) 3단계 evidence에 observation까지 채우고, 없으면(조건 3) chain만
	// 채운다.
	private InvestmentPracticeResponse buildChainResponse(
		Long userId, String tutorialKey, ResolvedPracticeChainDto chain) {
		Optional<ReferencePriceLines> referenceLines = referencePriceCalculator.calculate(chain);
		PracticeEvidenceResponse chainEvidence = new PracticeEvidenceResponse(
			chain.favoriteId(),
			chain.favoriteCreatedAt(),
			chain.intentionId(),
			chain.intentionCreatedAt(),
			chain.buyTradeId(),
			chain.buyTradeExecutedAt(),
			chain.holdingId(),
			referenceLines.map(ReferencePriceLines::referenceStopLossPrice).orElse(null),
			referenceLines.map(ReferencePriceLines::referenceTakeProfitPrice).orElse(null),
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null);

		List<PracticeMarketObservation> observations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(userId, chain.holdingId());
		Optional<PracticeMarketObservation> qualifyingObservation = observations.stream()
			.filter(observation -> observation.getEvidenceType() != null)
			.findFirst();

		PracticeEvidenceResponse stepThreeEvidence = qualifyingObservation
			.map(observation -> new PracticeEvidenceResponse(
				chainEvidence.favoriteId(),
				chainEvidence.favoriteCreatedAt(),
				chainEvidence.intentionId(),
				chainEvidence.intentionCreatedAt(),
				chainEvidence.buyTradeId(),
				chainEvidence.buyTradeExecutedAt(),
				chainEvidence.holdingId(),
				chainEvidence.referenceStopLossPrice(),
				chainEvidence.referenceTakeProfitPrice(),
				observation.getId(),
				observation.getObservedAt(),
				observation.getEvidenceType().name(),
				null,
				null,
				null,
				null,
				null))
			.orElse(chainEvidence);

		PracticeEvidenceResponse favoriteEvidence = PracticeEvidenceResponse
			.favoriteOnly(chain.favoriteId(), chain.favoriteCreatedAt());

		if (!chain.instrumentIsTutorialSample()) {
			List<PracticeStepResponse> steps = List.of(
				new PracticeStepResponse(1, STATUS_COMPLETED, false, favoriteEvidence),
				new PracticeStepResponse(2, STATUS_COMPLETED, false, chainEvidence),
				new PracticeStepResponse(3, STATUS_IN_PROGRESS, false, stepThreeEvidence));
			return new InvestmentPracticeResponse(tutorialKey, STATUS_IN_PROGRESS, 3, steps, null);
		}

		// 샘플 종목 chain: 4단계(매도·복기) 확장(plan.md "GET /api/education/practice 4단계 응답").
		LocalDateTime saleDeadlineAt = chain.buyTradeExecutedAt() == null
			? null
			: chain.buyTradeExecutedAt().plusMinutes(SALE_DEADLINE_MINUTES);
		String stepFourStatus = resolveStepFourStatus(chain, saleDeadlineAt);

		PracticeEvidenceResponse stepFourEvidence = new PracticeEvidenceResponse(
			chainEvidence.favoriteId(),
			chainEvidence.favoriteCreatedAt(),
			chainEvidence.intentionId(),
			chainEvidence.intentionCreatedAt(),
			chainEvidence.buyTradeId(),
			chainEvidence.buyTradeExecutedAt(),
			chainEvidence.holdingId(),
			chainEvidence.referenceStopLossPrice(),
			chainEvidence.referenceTakeProfitPrice(),
			null,
			null,
			null,
			null,
			null,
			chain.sellTradeId(),
			chain.sellTradeExecutedAt(),
			saleDeadlineAt);

		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, favoriteEvidence),
			new PracticeStepResponse(2, STATUS_COMPLETED, false, chainEvidence),
			new PracticeStepResponse(3, STATUS_IN_PROGRESS, false, stepThreeEvidence),
			new PracticeStepResponse(4, stepFourStatus, false, stepFourEvidence));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_IN_PROGRESS, 4, steps, null);
	}

	// 4단계(매도·복기) evidence 판정. (a) 매도 체결이 buyTrade.executedAt + 5분 이내여야 IN_PROGRESS(복기 대기),
	// 매도가 아직 없으면 그 5분 창이 지나기 전까지 AWAITING_SALE(잠기지 않음, 매도 유도), 매도 없이 5분을
	// 넘기거나 매도 자체가 5분을 넘겨 체결됐으면 EXPIRED다(plan.md "4. 5분 타이머"). 완료
	// (practice_completions)는 buildCompletedResponse가 담당하므로 이 메서드는 COMPLETED를 반환하지 않는다.
	// STATUS_NOT_STARTED를 쓰지 않는 이유: 이 API의 다른 모든 NOT_STARTED는 locked=true와 짝을 이루는데,
	// 4단계는 locked=false(지금 매도해야 하는 단계)이므로 같은 이름을 쓰면 프론트엔드가 기존 관례대로
	// CTA를 숨기는 오작동 위험이 있다(tester 지적, 이슈 #339).
	private String resolveStepFourStatus(ResolvedPracticeChainDto chain, LocalDateTime saleDeadlineAt) {
		if (chain.sellTradeId() != null) {
			return isWithinSaleDeadline(chain.sellTradeExecutedAt(), saleDeadlineAt)
				? STATUS_IN_PROGRESS
				: STATUS_EXPIRED;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		return isWithinSaleDeadline(now, saleDeadlineAt) ? STATUS_AWAITING_SALE : STATUS_EXPIRED;
	}

	// 경계값 포함(정확히 5분 시점 포함) — "!isAfter"로 5분 초과만 만료로 다룬다(plan.md 4번 "5분 경계값").
	private boolean isWithinSaleDeadline(LocalDateTime at, LocalDateTime saleDeadlineAt) {
		return saleDeadlineAt == null || !at.isAfter(saleDeadlineAt);
	}

	// 완료 조건 4: 유효 chain이 없지만 해당 market에 본인 favorite이 1개 이상이면 1단계만 COMPLETED다. 여러
	// favorite이 있으면 가장 이른 것을 대표로 쓴다(chain 해석이 모든 favorite에 대해 이미 실패했으므로 특정
	// favorite을 우선할 근거가 favorite.createdAt ASC뿐이다).
	private InvestmentPracticeResponse buildFavoriteOnlyResponse(
		String tutorialKey, List<FavoriteResponse> marketFavorites) {
		FavoriteResponse earliestFavorite = marketFavorites.stream()
			.min(Comparator.comparing(FavoriteResponse::createdAt))
			.orElseThrow();
		PracticeEvidenceResponse favoriteEvidence = PracticeEvidenceResponse
			.favoriteOnly(earliestFavorite.favoriteId(), earliestFavorite.createdAt());

		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, favoriteEvidence),
			new PracticeStepResponse(2, STATUS_IN_PROGRESS, false, favoriteEvidence),
			new PracticeStepResponse(3, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_IN_PROGRESS, 2, steps, null);
	}

	// 완료 조건 5: favorite조차 없으면 전부 미착수다.
	private InvestmentPracticeResponse buildNotStartedResponse(String tutorialKey) {
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_NOT_STARTED, false, PracticeEvidenceResponse.empty()),
			new PracticeStepResponse(2, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()),
			new PracticeStepResponse(3, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_NOT_STARTED, 1, steps, null);
	}

	// PracticeHoldingObservationService.resolveTutorialKey와 동일 패턴(이 spec 전체가 공유하는 관례).
	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> PracticeIntentionService.TUTORIAL_KEY;
			case CRYPTO -> PracticeIntentionService.COIN_TUTORIAL_KEY;
		};
	}
}
