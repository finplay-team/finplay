// holdingId로 evidence chain·A/B 관찰을 재검증해 실습 3단계 자유 복기를 저장하고 튜토리얼 완료를 확정하는 서비스
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.entity.PracticeProgress;
import com.finplay.api.domain.education.entity.PracticeProgressStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ai/specs/026-market-order-practice-tutorial plan.md "확정 HTTP·JSON 계약" 절의 {@code POST
 * /api/education/practice/holding-reflections} 처리 순서를 구현한다. {@code practice_progresses}를 잠근 뒤
 * evidence를 재검증하고, 복기·완료 저장과 progress 전이를 같은 트랜잭션에서 처리한다(plan.md "트랜잭션과 경합").
 */
@Service
@RequiredArgsConstructor
public class PracticeHoldingReflectionService {

	private static final short PROMPT_VERSION = 1;
	// 샘플 종목 chain 4단계 evidence의 매도 유효 기한(031/plan.md "4. 5분 타이머" anchor는
	// buyTrade.executedAt, InvestmentPracticeQueryService.isWithinSaleDeadline과 동일 정책).
	private static final long SALE_DEADLINE_MINUTES = 5;
	// 시장별 최초 완료 보상 금액(이슈 #343) — practice_completions의 UNIQUE(user_id, tutorial_key) 불변
	// (026 완료 불변 원칙)에 결합해 이 트랜잭션에서 정확히 1회만 지급된다.
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

		// practice_progresses(DB)를 먼저 잠근다 — 사전 의도 기록 시점에 이미 만들어진 행만 잠그며, 이 이슈에서
		// 새로 만들지 않는다(plan.md "트랜잭션과 경합", 지시사항). 행이 없으면 의도 기록을 거치지 않고 접근한
		// 것이므로 완료를 걸 진행 상태 자체가 없다 — 409 PRACTICE_EVIDENCE_MISSING.
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		if (progress.getStatus() == PracticeProgressStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}

		// chain 존재·holding 일치 여부는 evidence 재검증에 쓰고, 샘플 종목 여부는 4단계 매도 evidence
		// 전제조건 확장(031 SANDBOX-008) 분기에 쓴다.
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

		// 026의 전제조건(evidence A/B만)은 실제 종목 chain에서 그대로 유지한다. 샘플 종목 chain에만
		// 매도 evidence·5분 이내 전제조건을 추가로 요구한다(031 SANDBOX-008, plan.md 전제조건 표).
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

		// 현재 run 귀속 판정(risk snapshot 생성 시각 이후)만 남긴다. 매도 체결 이후 관찰을 배제하던 필터는
		// 제거했다 — 026 spec.md "비즈니스 규칙"이 "복기는 매도 여부와 무관하게 저장할 수 있다"로 못박았고
		// 031은 이 원칙을 그대로 상속한다. 그 필터 때문에 매도 후에 evidence를 채운 사용자는 관찰이 집계되지
		// 않아 영구히 409 PRACTICE_EVIDENCE_MISSING이었다(이슈 #420, 프로덕션 재현).
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
		// TUTORIAL-RESTART-006: practice_progresses를 FOR UPDATE로 잠근 뒤(기존 026/031 락 재사용) completion
		// 존재 여부를 읽는다 — 동시에 들어온 재완료 요청은 이 락으로 직렬화되어 두 번째 트랜잭션은 반드시
		// "이미 존재함"을 보게 된다(plan.md "동시성 설계").
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		// TUTORIAL-RESTART-004: 최초 완료 여부는 "이번 완료 트랜잭션 시작 시점에 practice_completions 행이
		// 이미 존재했는가"로만 판정한다(spec.md 비즈니스 규칙, 새 컬럼·백필 불필요).
		boolean alreadyCompletedBefore = practiceCompletionRepository
			.findByUserIdAndTutorialKey(userId, tutorialKey)
			.isPresent();

		if (alreadyCompletedBefore) {
			// TUTORIAL-RESTART-005/007: 재완료는 practice_completions·practice_market_reflections·
			// practice_progresses에 쓰지 않고(불변 완료 evidence 유지) 사용자가 입력한 answer도 영속하지
			// 않는다. attempt.status/completed_at만 갱신하고 보상은 건너뛴다.
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

	// 이슈 #343: 시장별 최초 완료에만 500만원을 그 시장 계좌에 지급한다.
	private void payTutorialCompletionReward(Long userId, Market market) {
		Account account = accountService.getAccountForUpdate(userId, market);
		account.addCash(TUTORIAL_COMPLETION_REWARD_AMOUNT);
	}

	// PracticeHoldingObservationService.resolveTutorialKey와 동일 패턴(지시사항)
	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> PracticeIntentionService.TUTORIAL_KEY;
			case CRYPTO -> PracticeIntentionService.COIN_TUTORIAL_KEY;
		};
	}

	// 031 SANDBOX-008 전제조건 표: 매도 체결이 없으면(5분 이내는 EVIDENCE_MISSING 재사용, 5분 초과는
	// TIME_EXPIRED), 매도 체결이 있어도 그 executedAt이 buyTrade.executedAt + 5분을 넘으면 TIME_EXPIRED다.
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

	// 031 SANDBOX-008의 5분 마감을 실제로 강제하는 자리다 — 응답의 saleDeadlineAt이 아니라 이 자체 상수가
	// 완료를 막는다. 041 SCENARIO-014가 시간 제한을 폐지했으므로 생성기 버전 2 attempt에서는 이 검증을
	// 수행하지 않는다. 매도 체결 자체가 없을 때 던지는 PRACTICE_EVIDENCE_MISSING은 유지한다 — 그건 시간이
	// 아니라 evidence 부재다. 버전 1 attempt와 legacy chain은 기존 동작 그대로다.
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

	// 경계값 포함(정확히 5분 시점 포함) — InvestmentPracticeQueryService.isWithinSaleDeadline과 동일 정책.
	private boolean isWithinSaleDeadline(LocalDateTime at, LocalDateTime saleDeadlineAt) {
		return !at.isAfter(saleDeadlineAt);
	}
}
