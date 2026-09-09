package com.finplay.api.domain.education.marketpractice.entity;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "practice_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeAttempt {

	private static final long INITIAL_RUN_NUMBER = 1L;
	private static final short SCENARIO_GENERATOR_VERSION = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Market market;

	@Column(name = "run_number", nullable = false)
	private long runNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private PracticeAttemptStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "instrument_id")
	private Instrument instrument;

	@Column(name = "anchor_at")
	private LocalDateTime anchorAt;

	@Column(name = "tutorial_date")
	private LocalDate tutorialDate;

	@Column(name = "price_seed")
	private Long priceSeed;

	@Column(name = "generator_version")
	private Short generatorVersion;

	@Getter(AccessLevel.NONE)
	@Enumerated(EnumType.STRING)
	@Column(name = "scenario_script_id", length = 32)
	private TutorialScenarioScriptId scenarioScriptId;

	@Column(name = "scenario_stage_id", length = 32)
	private String scenarioStageId;

	@Column(name = "scenario_stage_elapsed_seconds")
	private Long scenarioStageElapsedSeconds;

	@Column(name = "scenario_candle_open", precision = 18, scale = 8)
	private BigDecimal scenarioCandleOpen;

	@Column(name = "scenario_candle_high", precision = 18, scale = 8)
	private BigDecimal scenarioCandleHigh;

	@Column(name = "scenario_candle_low", precision = 18, scale = 8)
	private BigDecimal scenarioCandleLow;

	@Column(name = "scenario_progress_updated_at")
	private LocalDateTime scenarioProgressUpdatedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "exit_preset", length = 20)
	private ExitPreset exitPreset;

	@Column(name = "exit_stop_loss_rate", precision = 7, scale = 4)
	private BigDecimal exitStopLossRate;

	@Column(name = "exit_take_profit_rate", precision = 7, scale = 4)
	private BigDecimal exitTakeProfitRate;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	private PracticeAttempt(Long userId, Market market, LocalDateTime createdAt) {
		this.userId = userId;
		this.market = market;
		this.runNumber = INITIAL_RUN_NUMBER;
		this.status = PracticeAttemptStatus.SELECTING_INSTRUMENT;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public static PracticeAttempt create(Long userId, Market market, LocalDateTime createdAt) {
		return new PracticeAttempt(userId, market, createdAt);
	}

	public void selectInstrument(
		Instrument instrument,
		LocalDateTime anchorAt,
		LocalDate tutorialDate,
		long priceSeed,
		short generatorVersion,
		TutorialScenarioScriptId scenarioScriptId,
		LocalDateTime updatedAt) {
		if (this.status != PracticeAttemptStatus.SELECTING_INSTRUMENT) {
			throw new IllegalStateException("종목 선택 대기 상태에서만 종목을 선택할 수 있습니다.");
		}
		this.instrument = instrument;
		this.anchorAt = anchorAt;
		this.tutorialDate = tutorialDate;
		this.priceSeed = priceSeed;
		this.generatorVersion = generatorVersion;
		this.status = PracticeAttemptStatus.IN_PROGRESS;
		this.updatedAt = updatedAt;
		clearScenarioProgress();
		this.scenarioScriptId = scenarioScriptId;
	}

	public void restart(LocalDateTime updatedAt) {
		this.runNumber = Math.addExact(this.runNumber, 1L);
		this.status = PracticeAttemptStatus.SELECTING_INSTRUMENT;
		this.instrument = null;
		this.anchorAt = null;
		this.tutorialDate = null;
		this.priceSeed = null;
		this.generatorVersion = null;
		this.completedAt = null;
		this.updatedAt = updatedAt;
		this.exitPreset = null;
		this.exitStopLossRate = null;
		this.exitTakeProfitRate = null;
		clearScenarioProgress();
	}

	public void selectExitPreset(ExitPreset exitPreset, LocalDateTime updatedAt) {
		selectExitRates(ExitRates.of(exitPreset), updatedAt);
	}

	public void selectExitRates(ExitRates exitRates, LocalDateTime updatedAt) {
		if (this.status != PracticeAttemptStatus.IN_PROGRESS
			&& this.status != PracticeAttemptStatus.SELECTING_INSTRUMENT) {
			throw new IllegalStateException("진행 중인 튜토리얼 attempt만 손절·익절 기준을 고칠 수 있습니다.");
		}
		this.exitStopLossRate = exitRates.stopLossRate();
		this.exitTakeProfitRate = exitRates.takeProfitRate();
		this.exitPreset = exitRates.matchingPreset();
		this.updatedAt = updatedAt;
	}

	public ExitRates effectiveExitRates() {
		if (exitStopLossRate != null && exitTakeProfitRate != null) {
			return ExitRates.of(exitStopLossRate, exitTakeProfitRate);
		}
		return exitPreset == null ? ExitRates.DEFAULT : ExitRates.of(exitPreset);
	}

	public boolean exitRatesSelected() {
		return exitStopLossRate != null || exitPreset != null;
	}

	public boolean usesScenarioScript() {
		return generatorVersion != null && generatorVersion == SCENARIO_GENERATOR_VERSION;
	}

	public TutorialScenarioScriptId scenarioScriptId() {
		if (!usesScenarioScript()) {
			return null;
		}
		return scenarioScriptId == null ? TutorialScenarioScriptId.CRYPTO_STORY_V1 : scenarioScriptId;
	}

	public void advanceScenarioScript(TutorialScenarioScriptId scenarioScriptId, LocalDateTime updatedAt) {
		if (this.status != PracticeAttemptStatus.IN_PROGRESS) {
			throw new IllegalStateException("진행 중인 튜토리얼 attempt만 대본을 전환할 수 있습니다.");
		}
		clearScenarioProgress();
		this.scenarioScriptId = scenarioScriptId;
		this.updatedAt = updatedAt;
	}

	private void clearScenarioProgress() {
		this.scenarioScriptId = null;
		this.scenarioStageId = null;
		this.scenarioStageElapsedSeconds = null;
		this.scenarioCandleOpen = null;
		this.scenarioCandleHigh = null;
		this.scenarioCandleLow = null;
		this.scenarioProgressUpdatedAt = null;
	}

	public void startScenarioProgress(String stageId, BigDecimal openPrice, LocalDateTime progressUpdatedAt) {
		this.scenarioStageId = stageId;
		this.scenarioStageElapsedSeconds = 0L;
		this.scenarioCandleOpen = openPrice;
		this.scenarioCandleHigh = openPrice;
		this.scenarioCandleLow = openPrice;
		this.scenarioProgressUpdatedAt = progressUpdatedAt;
		this.updatedAt = progressUpdatedAt;
	}

	public void moveScenarioCursor(String stageId, long elapsedSeconds) {
		this.scenarioStageId = stageId;
		this.scenarioStageElapsedSeconds = elapsedSeconds;
	}

	public void extendScenarioCandle(BigDecimal price) {
		if (this.scenarioCandleHigh == null || this.scenarioCandleLow == null) {
			throw new IllegalStateException("진행 중 봉이 열리지 않은 attempt입니다.");
		}
		this.scenarioCandleHigh = this.scenarioCandleHigh.max(price);
		this.scenarioCandleLow = this.scenarioCandleLow.min(price);
	}

	public void markScenarioProgressed(LocalDateTime progressUpdatedAt) {
		this.scenarioProgressUpdatedAt = progressUpdatedAt;
		this.updatedAt = progressUpdatedAt;
	}

	public void complete(LocalDateTime completedAt) {
		if (this.status == PracticeAttemptStatus.COMPLETED) {
			throw new IllegalStateException("이미 완료된 튜토리얼 attempt입니다.");
		}
		if (this.status != PracticeAttemptStatus.IN_PROGRESS) {
			throw new IllegalStateException("진행 중인 튜토리얼 attempt만 완료할 수 있습니다.");
		}
		this.status = PracticeAttemptStatus.COMPLETED;
		this.completedAt = completedAt;
		this.updatedAt = completedAt;
	}

	public void reconcileCompletedReplay(
		Instrument instrument,
		LocalDateTime anchorAt,
		LocalDate tutorialDate,
		long priceSeed,
		short generatorVersion,
		LocalDateTime completedAt,
		LocalDateTime updatedAt) {
		if (this.status == PracticeAttemptStatus.COMPLETED) {
			return;
		}
		this.status = PracticeAttemptStatus.COMPLETED;
		this.instrument = instrument;
		this.anchorAt = anchorAt;
		this.tutorialDate = tutorialDate;
		this.priceSeed = priceSeed;
		this.generatorVersion = generatorVersion;
		this.completedAt = completedAt;
		this.updatedAt = updatedAt;
	}
}
