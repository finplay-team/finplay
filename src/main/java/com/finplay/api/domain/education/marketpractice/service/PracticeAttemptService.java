package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptService {

	private static final short SCENARIO_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_2;
	private static final short LEGACY_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_1;
	private static final short REPLAY_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_1;

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeProgressRepository practiceProgressRepository;
	private final InstrumentService instrumentService;
	private final TradeService tradeService;
	private final TutorialScenarioScriptLoader tutorialScenarioScriptLoader;
	private final TutorialAccountService tutorialAccountService;
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		String tutorialKey = resolveTutorialKey(market);
		LocalDateTime now = LocalDateTime.now(clock);
		PracticeAttempt existing = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElse(null);
		boolean inserted = existing == null;
		PracticeAttempt attempt = existing;
		if (inserted) {
			practiceAttemptRepository.insertIfAbsent(userId, market.name(), now);
			attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
				.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
		}
		TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
			userId, market, now);
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

	private short generatorVersionFor(Market market) {
		return tutorialScenarioScriptLoader.hasScript(market) ? SCENARIO_GENERATOR_VERSION : LEGACY_GENERATOR_VERSION;
	}

	private TutorialScenarioScriptId scenarioScriptIdFor(Market market, short generatorVersion) {
		return generatorVersion == SCENARIO_GENERATOR_VERSION && market == Market.CRYPTO
			? tutorialScenarioScriptLoader.firstScriptId(market)
			: null;
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
				return toResponse(attempt, tutorialAccountFor(userId, market));
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
		short generatorVersion = generatorVersionFor(market);
		attempt.selectInstrument(
			instrument,
			now,
			LocalDate.now(clock),
			secureRandom.nextLong(),
			generatorVersion,
			scenarioScriptIdFor(market, generatorVersion),
			now);
		return toResponse(attempt, tutorialAccountFor(userId, market));
	}

	@Transactional
	public PracticeAttemptResponse selectExitPreset(Long userId, Market market, ExitPreset preset) {
		return selectExitRates(userId, market, ExitRates.of(preset));
	}

	@Transactional
	public PracticeAttemptResponse selectExitRates(Long userId, Market market, ExitRates exitRates) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		requireStageUnlockedForPresetSelection(attempt);
		if (exitPresetLocked(attempt)) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
		attempt.selectExitRates(exitRates, LocalDateTime.now(clock));
		return toResponse(attempt, tutorialAccountFor(userId, market), false);
	}

	private void requireStageUnlockedForPresetSelection(PracticeAttempt attempt) {
		if (!attempt.usesScenarioScript()) {
			return;
		}
		PracticeStageProgressResponse progress = practiceStageProgressCalculationService.calculate(attempt);
		if (!progress.marketBuySellCompleted() || !progress.limitBuySellCompleted()) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}
	}

	private boolean exitPresetLocked(PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return false;
		}
		return tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber()).signum() > 0;
	}

	private void validateTutorialInstrument(Market market, Instrument instrument) {
		if (instrument.getMarket() != market || !instrument.isTutorialSample() || !instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
	}

	private PracticeAttemptResponse toResponse(PracticeAttempt attempt, TutorialAccount tutorialAccount) {
		return toResponse(attempt, tutorialAccount, exitPresetLocked(attempt));
	}

	private PracticeAttemptResponse toResponse(
		PracticeAttempt attempt, TutorialAccount tutorialAccount, boolean exitPresetLocked) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(
			attempt,
			snapshot,
			exitPresetLocked,
			tutorialAccount.getCashBalance(),
			tutorialAccount.getAvailableCash(),
			tutorialAccount.getRealizedPnl());
	}

	private TutorialAccount tutorialAccountFor(Long userId, Market market) {
		return tutorialAccountService.find(userId, market)
			.orElseGet(() -> tutorialAccountService.getOrCreateForUpdate(
				userId, market, LocalDateTime.now(clock)));
	}
}
