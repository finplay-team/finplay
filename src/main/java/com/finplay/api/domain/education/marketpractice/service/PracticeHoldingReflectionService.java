package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.education.entity.PracticeProgress;
import com.finplay.api.domain.education.entity.PracticeProgressStatus;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeHoldingReflectionService {

	private static final short PROMPT_VERSION = 1;
	private static final long SALE_DEADLINE_MINUTES = 5;
	private static final long TUTORIAL_COMPLETION_REWARD_AMOUNT = 5_000_000L;

	private final HoldingService holdingService;
	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeAttemptEvidenceService practiceAttemptEvidenceService;
	private final MarketPracticeChainResolutionService chainResolutionService;
	private final PracticeProgressRepository practiceProgressRepository;
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final PracticeMarketReflectionRepository practiceMarketReflectionRepository;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final AccountService accountService;
	private final Clock clock;

	@Transactional
	public PracticeHoldingReflectionResponse createReflection(
		Long userId, PracticeHoldingReflectionCreateRequest request) {
		Holding holding = holdingService.findHoldingForOwner(userId, request.holdingId())
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (holding.getInstrument().isTutorialSample()) {
			PracticeAttempt attempt = practiceAttemptRepository
				.findByUserIdAndMarketForUpdate(userId, holding.getInstrument().getMarket())
				.orElse(null);
			if (attempt != null) {
				return createAttemptReflection(userId, holding, request.answer(), attempt);
			}
		}

		String tutorialKey = resolveTutorialKey(holding.getInstrument().getMarket());

		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		if (progress.getStatus() == PracticeProgressStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}

		ResolvedPracticeChainDto resolvedChain = chainResolutionService
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

		if (resolvedChain.instrumentIsTutorialSample()) {
			verifySampleChainSaleEvidence(resolvedChain, now);
		}
		PracticeMarketReflection reflection = practiceMarketReflectionRepository.save(
			PracticeMarketReflection.create(userId, holding, tutorialKey, PROMPT_VERSION, request.answer(), now));

		practiceCompletionRepository.save(PracticeCompletion.create(userId, tutorialKey, reflection, now));
		progress.complete(now);
		payTutorialCompletionReward(userId, holding.getInstrument().getMarket());

		return PracticeHoldingReflectionResponse.from(reflection, true);
	}

	private PracticeHoldingReflectionResponse createAttemptReflection(
		Long userId, Holding holding, String answer, PracticeAttempt attempt) {
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		ResolvedPracticeAttemptEvidenceDto evidence = practiceAttemptEvidenceService
			.requireCurrentRun(attempt, userId, holding.getId());
		LocalDateTime now = LocalDateTime.now(clock);
		verifyAttemptSaleEvidence(attempt, evidence, now);

		boolean hasEvidence = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(userId, holding.getId())
			.stream()
			.filter(observation -> !observation.getObservedAt()
				.isBefore(evidence.observationBaseline().getCreatedAt()))
			.anyMatch(observation -> observation.getEvidenceType() != null);
		if (!hasEvidence) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}

		String tutorialKey = resolveTutorialKey(attempt.getMarket());
		practiceProgressRepository.insertIfAbsent(userId, tutorialKey, attempt.getCreatedAt());
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		boolean alreadyCompletedBefore = practiceCompletionRepository
			.findByUserIdAndTutorialKey(userId, tutorialKey)
			.isPresent();

		if (alreadyCompletedBefore) {
			attempt.complete(now);
			return PracticeHoldingReflectionResponse.ofRecompletion(holding.getId(), answer, now);
		}

		PracticeMarketReflection reflection = practiceMarketReflectionRepository.save(
			PracticeMarketReflection.create(userId, holding, tutorialKey, PROMPT_VERSION, answer, now));
		practiceCompletionRepository.save(PracticeCompletion.create(userId, tutorialKey, reflection, now));
		progress.complete(now);
		attempt.complete(now);
		payTutorialCompletionReward(userId, attempt.getMarket());
		return PracticeHoldingReflectionResponse.from(reflection, true);
	}

	private void payTutorialCompletionReward(Long userId, Market market) {
		Account account = accountService.getAccountForUpdate(userId, market);
		account.addCash(TUTORIAL_COMPLETION_REWARD_AMOUNT);
	}

	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> PracticeIntentionService.TUTORIAL_KEY;
			case CRYPTO -> PracticeIntentionService.COIN_TUTORIAL_KEY;
		};
	}

	private void verifySampleChainSaleEvidence(ResolvedPracticeChainDto resolvedChain, LocalDateTime now) {
		LocalDateTime saleDeadlineAt = resolvedChain.buyTradeExecutedAt().plusMinutes(SALE_DEADLINE_MINUTES);
		if (resolvedChain.sellTradeId() == null) {
			if (isWithinSaleDeadline(now, saleDeadlineAt)) {
				throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
			}
			throw new BusinessException(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED);
		}
		if (!isWithinSaleDeadline(resolvedChain.sellTradeExecutedAt(), saleDeadlineAt)) {
			throw new BusinessException(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED);
		}
	}

	private void verifyAttemptSaleEvidence(
		PracticeAttempt attempt, ResolvedPracticeAttemptEvidenceDto evidence, LocalDateTime now) {
		if (attempt.usesScenarioScript()) {
			if (evidence.sellTrade() == null) {
				throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
			}
			return;
		}
		LocalDateTime saleDeadlineAt = evidence.riskSnapshot().getBuyTrade().getExecutedAt()
			.plusMinutes(SALE_DEADLINE_MINUTES);
		if (evidence.sellTrade() == null) {
			if (isWithinSaleDeadline(now, saleDeadlineAt)) {
				throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
			}
			throw new BusinessException(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED);
		}
		if (!isWithinSaleDeadline(evidence.sellTrade().getExecutedAt(), saleDeadlineAt)) {
			throw new BusinessException(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED);
		}
	}

	private boolean isWithinSaleDeadline(LocalDateTime at, LocalDateTime saleDeadlineAt) {
		return !at.isAfter(saleDeadlineAt);
	}
}
