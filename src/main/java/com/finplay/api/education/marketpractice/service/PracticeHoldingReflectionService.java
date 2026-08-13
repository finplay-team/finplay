// holdingId로 evidence chain·A/B 관찰을 재검증해 실습 3단계 자유 복기를 저장하고 튜토리얼 완료를 확정하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
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
	// 샘플 종목 chain 4단계 evidence의 매도 유효 기한(031/plan.md "4. 5분 타이머" anchor는
	// buyTrade.executedAt, InvestmentPracticeQueryService.isWithinSaleDeadline과 동일 정책).
	private static final long SALE_DEADLINE_MINUTES = 5;
	// 시장별 최초 완료 보상 금액(이슈 #343) — practice_completions의 UNIQUE(user_id, tutorial_key) 불변
	// (026 완료 불변 원칙)에 결합해 이 트랜잭션에서 정확히 1회만 지급된다.
	private static final long TUTORIAL_COMPLETION_REWARD_AMOUNT = 5_000_000L;

	private final HoldingService holdingService;
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

		return PracticeHoldingReflectionResponse.from(reflection);
	}

	// 이슈 #343: 시장별 최초 완료에만 500만원을 그 시장 계좌에 지급한다. OrderExecutionService
	// .getAccountForUpdateFor와 동일 패턴으로 market 도메인의 Market을 account 도메인의 Market으로 변환한다.
	private void payTutorialCompletionReward(Long userId, Market market) {
		com.finplay.api.account.domain.Market accountMarket = com.finplay.api.account.domain.Market
			.valueOf(market.name());
		Account account = accountService.getAccountForUpdate(userId, accountMarket);
		account.addCash(TUTORIAL_COMPLETION_REWARD_AMOUNT);
		// 튜토리얼 완료 보상은 정의상 항상 샌드박스 기원이므로 종목 조건 없이 항상 누적한다(spec 033
		// SANDBOX-EXCL-006).
		account.addSandboxCashAdjustment(TUTORIAL_COMPLETION_REWARD_AMOUNT);
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

	// 경계값 포함(정확히 5분 시점 포함) — InvestmentPracticeQueryService.isWithinSaleDeadline과 동일 정책.
	private boolean isWithinSaleDeadline(LocalDateTime at, LocalDateTime saleDeadlineAt) {
		return !at.isAfter(saleDeadlineAt);
	}
}
