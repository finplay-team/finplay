// attempt의 영속 seed·anchor·run을 market 순수 생성기 입력과 현재 canonical 가격으로 해석하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.TutorialPriceGenerationInput;
import com.finplay.api.market.service.TutorialPriceGenerator;
import com.finplay.api.market.service.TutorialPriceSeriesDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptCanonicalPriceService {

	public static final int SECONDS_PER_VIRTUAL_MINUTE = 3;
	private final PracticeAttemptRepository practiceAttemptRepository;
	private final TutorialPriceGenerator tutorialPriceGenerator;

	public BigDecimal canonicalPrice(PracticeAttempt attempt, LocalDateTime observedAt) {
		return tutorialPriceGenerator.canonicalPrice(toInput(attempt), publishedMinute(attempt, observedAt));
	}

	public TutorialPriceSeriesDto priceSeries(PracticeAttempt attempt, LocalDateTime observedAt) {
		return tutorialPriceGenerator.generate(toInput(attempt), publishedMinute(attempt, observedAt));
	}

	@Transactional(readOnly = true)
	public BigDecimal canonicalPriceForMutation(Long userId, Instrument instrument, LocalDateTime observedAt) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarket(userId, instrument.getMarket())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		validateSelectedInstrument(attempt, instrument.getId());
		return canonicalPrice(attempt, observedAt);
	}

	public long publishedMinute(PracticeAttempt attempt, LocalDateTime observedAt) {
		validateSelectedInstrument(attempt, attempt.getInstrument() == null ? null : attempt.getInstrument().getId());
		long elapsedSeconds = Duration.between(attempt.getAnchorAt(), observedAt).getSeconds();
		return elapsedSeconds <= 0 ? 0L : elapsedSeconds / SECONDS_PER_VIRTUAL_MINUTE;
	}

	private TutorialPriceGenerationInput toInput(PracticeAttempt attempt) {
		validateSelectedInstrument(attempt, attempt.getInstrument() == null ? null : attempt.getInstrument().getId());
		return new TutorialPriceGenerationInput(
			attempt.getGeneratorVersion(),
			attempt.getPriceSeed(),
			attempt.getInstrument().getId(),
			attempt.getRunNumber(),
			attempt.getMarket(),
			attempt.getTutorialDate());
	}

	private void validateSelectedInstrument(PracticeAttempt attempt, Long instrumentId) {
		if (instrumentId == null
			|| attempt.getInstrument() == null
			|| !attempt.getInstrument().getId().equals(instrumentId)
			|| attempt.getAnchorAt() == null
			|| attempt.getTutorialDate() == null
			|| attempt.getPriceSeed() == null
			|| attempt.getGeneratorVersion() == null) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
	}
}
