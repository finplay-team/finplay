// 튜토리얼 attempt의 멱등 진입 조회와 현재 실행 종목 선택을 처리하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.TutorialAccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.TutorialPriceGenerator;
import com.finplay.api.market.service.TutorialScenarioScriptLoader;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptService {

	// 041 5번에서 1 -> 2. 커서를 전진시키는 진행 계산(PracticeScenarioProgressService)이 생긴 지금이 그
	// 시점이다 — 그전에 올렸으면 새 사용자의 가격이 0막 0분에 고정되고 지정가 정산도 대본 위치를 못 읽었다.
	// 배포 시점에 진행 중이던 버전 1 attempt는 재시작을 강제하지 않고 그대로 버전 1로 재현한다
	// (041 plan §생성기 버전 2) — 새 attempt와 재시작 후 다시 종목을 고른 attempt만 버전 2를 받는다.
	//
	// **대본이 저작된 시장에서만 준다.** 041은 CRYPTO 대본 하나만 저작했고 STOCK 대본은 SCENARIO-024의
	// 후속이다. 시장을 가리지 않고 2를 주면 STOCK 튜토리얼이 모든 가격 조회에서 "대본이 저작되지 않은
	// 시장입니다"로 터진다(통합 테스트에서 재현). STOCK 대본이 들어오면 이 판정이 자동으로 따라간다.
	private static final short SCENARIO_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_2;
	private static final short LEGACY_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_1;
	// legacy completion만 있는 사용자에게 만들어 주는 읽기 전용 replay attempt는 대본 커서가 없고 tick도
	// 돌지 않는다. 버전 2를 주면 대본 첫 구간 0분에 고정된 평평한 차트가 되므로 기존 재현을 그대로 둔다.
	private static final short REPLAY_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_1;

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeProgressRepository practiceProgressRepository;
	private final InstrumentService instrumentService;
	private final TutorialScenarioScriptLoader tutorialScenarioScriptLoader;
	private final TutorialAccountService tutorialAccountService;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	@Transactional
	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		String tutorialKey = resolveTutorialKey(market);
		LocalDateTime now = LocalDateTime.now(clock);
		boolean inserted = practiceAttemptRepository.insertIfAbsent(userId, market.name(), now) == 1;
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
		// PracticeAttempt 행 잠금이 이미 사용자·시장 조합을 직렬화하므로, 같은 트랜잭션 안에서 튜토리얼 계좌도
		// 함께 get-or-create한다(TUTORIAL-CASH-ISOL-001, 설계 판단 — 계좌 생성 시점). 그 결과를 응답에도 그대로
		// 실어 보낸다(TUTORIAL-CASH-ISOL-011).
		TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
			userId, toAccountMarket(market), now);
		practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey);
		PracticeCompletion completion = practiceCompletionRepository
			.findByUserIdAndTutorialKey(userId, tutorialKey)
			.orElse(null);
		if (completion != null) {
			if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
				return toResponse(attempt, tutorialAccount);
			}
			if (inserted) {
				initializeCompletedReplay(userId, market, attempt, completion, now);
			}
			// completion evidence는 있지만 attempt가 COMPLETED가 아닌 기존 행은 재시작 후 진행 중인
			// 상태(TUTORIAL-RESTART-003)이므로 오류로 취급하지 않고 현재 상태를 그대로 반환한다.
		}
		return toResponse(attempt, tutorialAccount);
	}

	private void initializeCompletedReplay(
		Long userId, Market market, PracticeAttempt attempt, PracticeCompletion completion, LocalDateTime updatedAt) {
		Instrument instrument = requireCompletionInstrument(market, completion);
		LocalDateTime completedAt = completion.getCompletedAt();
		attempt.reconcileCompletedReplay(
			instrument,
			completedAt,
			completedAt.toLocalDate(),
			deterministicReplaySeed(userId, market, completion.getId(), instrument.getId()),
			REPLAY_GENERATOR_VERSION,
			completedAt,
			updatedAt);
	}

	private Instrument requireCompletionInstrument(Market market, PracticeCompletion completion) {
		Instrument instrument = completion.getReflection().getHolding().getInstrument();
		if (instrument.getMarket() != market) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		return instrument;
	}

	private long deterministicReplaySeed(Long userId, Market market, Long completionId, Long instrumentId) {
		long seed = 0xcbf29ce484222325L;
		seed = (seed ^ userId) * 0x100000001b3L;
		seed = (seed ^ market.ordinal()) * 0x100000001b3L;
		seed = (seed ^ completionId) * 0x100000001b3L;
		return (seed ^ instrumentId) * 0x100000001b3L;
	}

	// PracticeHoldingReflectionService.payTutorialCompletionReward와 동일 패턴으로 market 도메인의 Market을
	// account 도메인의 Market으로 변환한다(두 Market은 값 집합이 같지만 서로 다른 열거형이다).
	private com.finplay.api.account.domain.Market toAccountMarket(Market market) {
		return com.finplay.api.account.domain.Market.valueOf(market.name());
	}

	private short generatorVersionFor(Market market) {
		return tutorialScenarioScriptLoader.hasScript(market) ? SCENARIO_GENERATOR_VERSION : LEGACY_GENERATOR_VERSION;
	}

	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> "INVESTMENT_PRACTICE_V1";
			case CRYPTO -> "COIN_PRACTICE_V1";
		};
	}

	@Transactional
	public PracticeAttemptResponse selectInstrument(Long userId, Market market, Long instrumentId) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		validateTutorialInstrument(market, instrument);

		if (attempt.getStatus() == PracticeAttemptStatus.IN_PROGRESS) {
			if (attempt.getInstrument().getId().equals(instrumentId)) {
				return toResponse(attempt);
			}
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		if (attempt.getStatus() != PracticeAttemptStatus.SELECTING_INSTRUMENT) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		attempt.selectInstrument(
			instrument, now, LocalDate.now(clock), secureRandom.nextLong(), generatorVersionFor(market), now);
		return toResponse(attempt);
	}

	private void validateTutorialInstrument(Market market, Instrument instrument) {
		if (instrument.getMarket() != market || !instrument.isTutorialSample() || !instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
	}

	private PracticeAttemptResponse toResponse(PracticeAttempt attempt) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(attempt, snapshot);
	}

	// 진입 응답(ensureAttempt)은 같은 트랜잭션에서 이미 get-or-create한 튜토리얼 계좌 값을 그대로 실어
	// 보낸다(TUTORIAL-CASH-ISOL-011) — 추가 조회 없이 진입 시점 잔고·손익을 정확히 노출한다.
	private PracticeAttemptResponse toResponse(PracticeAttempt attempt, TutorialAccount tutorialAccount) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(
			attempt,
			snapshot,
			tutorialAccount.getCashBalance(),
			tutorialAccount.getAvailableCash(),
			tutorialAccount.getRealizedPnl());
	}
}
