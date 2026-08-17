// 튜토리얼 attempt의 멱등 진입 조회와 현재 실행 종목 선택을 처리하는 서비스
package com.finplay.api.education.marketpractice.service;

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

	private static final short GENERATOR_VERSION = 1;

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeProgressRepository practiceProgressRepository;
	private final InstrumentService instrumentService;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	@Transactional
	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		String tutorialKey = resolveTutorialKey(market);
		LocalDateTime now = LocalDateTime.now(clock);
		boolean inserted = practiceAttemptRepository.insertIfAbsent(userId, market.name(), now) == 1;
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
		practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey);
		PracticeCompletion completion = practiceCompletionRepository
			.findByUserIdAndTutorialKey(userId, tutorialKey)
			.orElse(null);
		if (completion != null) {
			if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
				return toResponse(attempt);
			}
			if (inserted) {
				initializeCompletedReplay(userId, market, attempt, completion, now);
			}
			// completion evidence는 있지만 attempt가 COMPLETED가 아닌 기존 행은 재시작 후 진행 중인
			// 상태(TUTORIAL-RESTART-003)이므로 오류로 취급하지 않고 현재 상태를 그대로 반환한다.
		}
		return toResponse(attempt);
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
			GENERATOR_VERSION,
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
			instrument, now, LocalDate.now(clock), secureRandom.nextLong(), GENERATOR_VERSION, now);
		return toResponse(attempt);
	}

	private void validateTutorialInstrument(Market market, Instrument instrument) {
		if (instrument.getMarket() != market || !instrument.isTutorialSample() || !instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
	}

	private PracticeAttemptResponse toResponse(PracticeAttempt attempt) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(attempt, snapshot);
	}
}
