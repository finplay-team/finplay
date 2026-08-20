// attempt의 영속 seed·anchor·run 또는 대본 커서를 market 순수 생성기 입력과 현재 canonical 가격으로 해석하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.TutorialPriceCandleDto;
import com.finplay.api.market.service.TutorialPriceGenerationInput;
import com.finplay.api.market.service.TutorialPriceGenerator;
import com.finplay.api.market.service.TutorialPriceSeriesDto;
import com.finplay.api.market.service.TutorialScenarioCursor;
import com.finplay.api.market.service.TutorialScenarioScript;
import com.finplay.api.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.market.service.TutorialScenarioStage;
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

	// 생성기 버전 2에서 observedAt은 쓰이지 않는다 — 가격이 (구간, 구간 내 분)의 함수라 "가상 4분에 해당하는
	// 벽시계 시각"이 존재하지 않기 때문이다. 시그니처를 유지한 채 내부 파생만 바꿨으므로 order 도메인의 공개
	// 계약(lockForFill → canonicalPrice)은 그대로다(041 plan §`order` 인터페이스 변경).
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

	/**
	 * "그때 팔지 않았다면"의 기준 가격 (041 SCENARIO-021). 대본을 쓰지 않는 실행은 {@code null}이다.
	 *
	 * <p><b>완료 여부로 기준이 갈린다.</b> 진행 중에는 <b>현재 커서 가격</b>이라 화면의 선이 대본과 함께
	 * 움직이고, 완료 응답에서는 <b>대본의 마지막 진행 구간 끝 가격</b>이라 사용자가 실제로 그 지점까지
	 * 진행했는지와 무관한 대조가 된다 — 손절 뒤 재매수하지 않고 나간 사용자도 같은 대조를 얻어야 하기
	 * 때문이다(SCENARIO-021의 2026-08-18 정정).
	 *
	 * <p><b>진행 중에 종점 가격을 쓰지 않는 이유</b>는 그것이 곧 이야기의 결말을 미리 알려주기 때문이다.
	 * 4막 폭락 가격이 2막에서 화면에 뜨면 사건 노출 게이트(SCENARIO-015)보다 더 큰 누설이 된다.
	 */
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
		// 로더가 "마지막 구간은 진행 구간"을 기동 시점에 강제하므로 마지막 구간의 끝이 곧 대본의 결말이다.
		TutorialScenarioStage last = script.stages().get(script.stages().size() - 1);
		return new TutorialScenarioCursor(last.id(), last.minutes() - 1);
	}

	public TutorialScenarioScript script(PracticeAttempt attempt) {
		return tutorialScenarioScriptLoader.script(attempt.getMarket());
	}

	// 대본 위치가 비어 있으면 미시작이다 — 종목 선택·재시작이 다섯 컬럼을 전부 null로 지운다(041 3번이 남긴
	// 계약). 첫 tick이 오기 전의 조회·주문 접수는 대본의 첫 구간 0분 가격을 본다.
	public TutorialScenarioCursor cursor(PracticeAttempt attempt, TutorialScenarioScript script) {
		if (attempt.getScenarioStageId() == null || attempt.getScenarioStageElapsedSeconds() == null) {
			return new TutorialScenarioCursor(script.firstStage().id(), 0);
		}
		TutorialScenarioStage stage = script.stage(attempt.getScenarioStageId());
		long minute = attempt.getScenarioStageElapsedSeconds() / SECONDS_PER_VIRTUAL_MINUTE;
		// 마지막 구간을 다 쓰면 elapsed가 구간 길이에 닿은 채로 멈춘다(FINISHED). 그때 파생 분은 구간을
		// 벗어나므로 마지막 분으로 clamp해 마지막 가격을 유지한다(041 plan §상태 전이표 4행).
		return new TutorialScenarioCursor(stage.id(), (int)Math.min(minute, stage.minutes() - 1L));
	}

	public long publishedMinute(PracticeAttempt attempt, LocalDateTime observedAt) {
		validateSelectedInstrument(attempt, attempt.getInstrument() == null ? null : attempt.getInstrument().getId());
		long elapsedSeconds = Duration.between(attempt.getAnchorAt(), observedAt).getSeconds();
		return elapsedSeconds <= 0 ? 0L : elapsedSeconds / SECONDS_PER_VIRTUAL_MINUTE;
	}

	// 29개 완결 일봉은 버전 1과 같은 seed 생성이고, 진행 중 1봉만 대본에서 만든다. 봉 3값은 지나온 경로를
	// 복원할 수 없어 attempt에 누적돼 있으므로(041 plan §데이터 모델) 여기서 되접지 않고 그대로 읽는다.
	private TutorialPriceSeriesDto scenarioSeries(PracticeAttempt attempt) {
		TutorialPriceGenerationInput input = toInput(attempt);
		TutorialScenarioScript script = script(attempt);
		BigDecimal close = tutorialPriceGenerator.canonicalPrice(input, script, cursor(attempt, script));
		BigDecimal open = attempt.getScenarioCandleOpen() == null ? close : attempt.getScenarioCandleOpen();
		BigDecimal high = attempt.getScenarioCandleHigh() == null ? close : attempt.getScenarioCandleHigh();
		BigDecimal low = attempt.getScenarioCandleLow() == null ? close : attempt.getScenarioCandleLow();
		List<TutorialPriceCandleDto> candles = new ArrayList<>(tutorialPriceGenerator.generateHistory(input));
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
