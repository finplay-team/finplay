package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.service.TutorialPriceCandleDto;
import com.finplay.api.domain.market.service.TutorialPriceGenerationInput;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialPriceSeriesDto;
import com.finplay.api.domain.market.service.TutorialScenarioCursor;
import com.finplay.api.domain.market.service.TutorialScenarioScript;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.market.service.TutorialScenarioStage;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptCanonicalPriceService {

	public static final int SECONDS_PER_VIRTUAL_MINUTE = 3;
	private final PracticeAttemptRepository practiceAttemptRepository;
	private final TutorialPriceGenerator tutorialPriceGenerator;
	private final TutorialScenarioScriptLoader tutorialScenarioScriptLoader;

	public BigDecimal canonicalPrice(PracticeAttempt attempt, LocalDateTime observedAt) {
		if (attempt.usesScenarioScript()) {
			TutorialScenarioScript script = script(attempt);
			return tutorialPriceGenerator.canonicalPrice(toInput(attempt), script, cursor(attempt, script));
		}
		return tutorialPriceGenerator.canonicalPrice(toInput(attempt), publishedMinute(attempt, observedAt));
	}

	public TutorialPriceSeriesDto priceSeries(PracticeAttempt attempt, LocalDateTime observedAt) {
		if (attempt.usesScenarioScript()) {
			return scenarioSeries(attempt);
		}
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

	public BigDecimal postSellComparisonPrice(PracticeAttempt attempt) {
		if (!attempt.usesScenarioScript() || attempt.getInstrument() == null) {
			return null;
		}
		TutorialScenarioScript script = script(attempt);
		return tutorialPriceGenerator.canonicalPrice(toInput(attempt), script, comparisonCursor(attempt, script));
	}

	private TutorialScenarioCursor comparisonCursor(PracticeAttempt attempt, TutorialScenarioScript script) {
		if (attempt.getStatus() != PracticeAttemptStatus.COMPLETED) {
			return cursor(attempt, script);
		}
		TutorialScenarioStage last = script.stages().get(script.stages().size() - 1);
		return new TutorialScenarioCursor(last.id(), last.minutes() - 1);
	}

	public TutorialScenarioScript script(PracticeAttempt attempt) {
		return tutorialScenarioScriptLoader.script(attempt.scenarioScriptId());
	}

	public TutorialScenarioCursor cursor(PracticeAttempt attempt, TutorialScenarioScript script) {
		if (attempt.getScenarioStageId() == null || attempt.getScenarioStageElapsedSeconds() == null) {
			return new TutorialScenarioCursor(script.firstStage().id(), 0);
		}
		TutorialScenarioStage stage = script.stage(attempt.getScenarioStageId());
		long minute = attempt.getScenarioStageElapsedSeconds() / SECONDS_PER_VIRTUAL_MINUTE;
		return new TutorialScenarioCursor(stage.id(), (int)Math.min(minute, stage.minutes() - 1L));
	}

	public long publishedMinute(PracticeAttempt attempt, LocalDateTime observedAt) {
		validateSelectedInstrument(attempt, attempt.getInstrument() == null ? null : attempt.getInstrument().getId());
		long elapsedSeconds = Duration.between(attempt.getAnchorAt(), observedAt).getSeconds();
		return elapsedSeconds <= 0 ? 0L : elapsedSeconds / SECONDS_PER_VIRTUAL_MINUTE;
	}

	private TutorialPriceSeriesDto scenarioSeries(PracticeAttempt attempt) {
		TutorialPriceGenerationInput input = toInput(attempt);
		TutorialScenarioScript script = script(attempt);
		BigDecimal close = tutorialPriceGenerator.canonicalPrice(input, script, cursor(attempt, script));
		BigDecimal open = attempt.getScenarioCandleOpen() == null ? close : attempt.getScenarioCandleOpen();
		BigDecimal high = attempt.getScenarioCandleHigh() == null ? close : attempt.getScenarioCandleHigh();
		BigDecimal low = attempt.getScenarioCandleLow() == null ? close : attempt.getScenarioCandleLow();
		List<TutorialPriceCandleDto> candles = new ArrayList<>(tutorialPriceGenerator.generateHistory(input, script));
		candles.add(new TutorialPriceCandleDto(
			attempt.getTutorialDate(), open, high.max(close), low.min(close), close, true));
		return new TutorialPriceSeriesDto(List.copyOf(candles), close);
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
