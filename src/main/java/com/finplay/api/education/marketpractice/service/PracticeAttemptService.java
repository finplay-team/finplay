// 튜토리얼 attempt의 멱등 진입 조회와 현재 실행 종목 선택을 처리하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
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
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final InstrumentService instrumentService;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	@Transactional
	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		LocalDateTime now = LocalDateTime.now(clock);
		practiceAttemptRepository.insertIfAbsent(userId, market.name(), now);
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarket(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
		return toResponse(attempt);
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
