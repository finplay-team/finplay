// 튜토리얼 attempt의 실행 세대별 최초 매수 체결가와 교육용 손절·익절 가격을 보존하는 불변 엔티티
package com.finplay.api.education.marketpractice.domain;

import com.finplay.api.order.domain.Trade;
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

	// 한 실행 세대 안의 몇 번째 진입인가. 손절 후 재매수하면 2가 된다(042 EXITPRESET-019).
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

	// 이 진입에 적용된 프리셋. null은 기능 도입 전에 만들어진 행이며 기본 프리셋으로 해석한다
	// (042 EXITPRESET-002). 새로 만드는 행은 항상 채워진다 — 미선택 사용자도 기본 프리셋이 확정된다.
	@Enumerated(EnumType.STRING)
	@Column(name = "exit_preset", length = 20)
	private ExitPreset exitPreset;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PracticeRiskSnapshot(
		PracticeAttempt attempt,
		long runNumber,
		int entrySequence,
		ExitPreset exitPreset,
		Trade buyTrade,
		BigDecimal entryPrice,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		LocalDateTime createdAt) {
		this.attempt = attempt;
		this.runNumber = runNumber;
		this.entrySequence = entrySequence;
		this.exitPreset = exitPreset;
		this.buyTrade = buyTrade;
		this.entryPrice = entryPrice;
		this.stopLossPrice = stopLossPrice;
		this.takeProfitPrice = takeProfitPrice;
		this.createdAt = createdAt;
	}

	public static PracticeRiskSnapshot create(
		PracticeAttempt attempt,
		long runNumber,
		int entrySequence,
		ExitPreset exitPreset,
		Trade buyTrade,
		BigDecimal entryPrice,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		LocalDateTime createdAt) {
		if (entrySequence < FIRST_ENTRY_SEQUENCE) {
			throw new IllegalArgumentException("진입 순번은 1 이상이어야 합니다.");
		}
		return new PracticeRiskSnapshot(
			attempt, runNumber, entrySequence, exitPreset, buyTrade, entryPrice, stopLossPrice, takeProfitPrice,
			createdAt);
	}
}
