package com.finplay.api.domain.education.marketpractice.entity;

import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.entity.Trade;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "practice_risk_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeRiskSnapshot {

	public static final int FIRST_ENTRY_SEQUENCE = 1;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "attempt_id", nullable = false)
	private PracticeAttempt attempt;

	@Column(name = "run_number", nullable = false)
	private long runNumber;

	@Column(name = "entry_sequence", nullable = false)
	private int entrySequence = FIRST_ENTRY_SEQUENCE;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buy_trade_id", nullable = false)
	private Trade buyTrade;

	@Column(name = "entry_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal entryPrice;

	@Column(name = "stop_loss_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal stopLossPrice;

	@Column(name = "take_profit_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal takeProfitPrice;

	@Enumerated(EnumType.STRING)
	@Column(name = "exit_preset", length = 20)
	private ExitPreset exitPreset;

	@Column(name = "exit_stop_loss_rate", precision = 7, scale = 4)
	private BigDecimal exitStopLossRate;

	@Column(name = "exit_take_profit_rate", precision = 7, scale = 4)
	private BigDecimal exitTakeProfitRate;

	@Enumerated(EnumType.STRING)
	@Column(name = "scenario_script_id", length = 32)
	private TutorialScenarioScriptId scenarioScriptId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PracticeRiskSnapshot(
		PracticeAttempt attempt,
		long runNumber,
		int entrySequence,
		ExitRates exitRates,
		Trade buyTrade,
		BigDecimal entryPrice,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		TutorialScenarioScriptId scenarioScriptId,
		LocalDateTime createdAt) {
		this.attempt = attempt;
		this.runNumber = runNumber;
		this.entrySequence = entrySequence;
		this.exitStopLossRate = exitRates.stopLossRate();
		this.exitTakeProfitRate = exitRates.takeProfitRate();
		this.exitPreset = exitRates.matchingPreset();
		this.buyTrade = buyTrade;
		this.entryPrice = entryPrice;
		this.stopLossPrice = stopLossPrice;
		this.takeProfitPrice = takeProfitPrice;
		this.scenarioScriptId = scenarioScriptId;
		this.createdAt = createdAt;
	}

	public static PracticeRiskSnapshot create(
		PracticeAttempt attempt,
		long runNumber,
		int entrySequence,
		ExitRates exitRates,
		Trade buyTrade,
		BigDecimal entryPrice,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		TutorialScenarioScriptId scenarioScriptId,
		LocalDateTime createdAt) {
		if (entrySequence < FIRST_ENTRY_SEQUENCE) {
			throw new IllegalArgumentException("진입 순번은 1 이상이어야 합니다.");
		}
		return new PracticeRiskSnapshot(
			attempt, runNumber, entrySequence, exitRates, buyTrade, entryPrice, stopLossPrice, takeProfitPrice,
			scenarioScriptId, createdAt);
	}

	public ExitRates appliedExitRates() {
		if (exitStopLossRate != null && exitTakeProfitRate != null) {
			return ExitRates.of(exitStopLossRate, exitTakeProfitRate);
		}
		return exitPreset == null ? ExitRates.DEFAULT : ExitRates.of(exitPreset);
	}
}
