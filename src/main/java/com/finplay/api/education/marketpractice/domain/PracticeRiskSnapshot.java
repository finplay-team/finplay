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

	// 한 실행 세대 안의 몇 번째 진입인가. 재진입(손절 후 재매수) 도입 전까지는 항상 1이다.
	// 값을 실제로 채우는 것은 042 tasks 4번이며, 여기서는 조회를 진입 단위로 나누기 위한 매핑만 더한다.
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
	// (042 EXITPRESET-002). 값을 실제로 채우는 것은 042 tasks 4번이다 — entry_sequence와 같은 이유로
	// 여기서는 매핑만 더한다.
	@Enumerated(EnumType.STRING)
	@Column(name = "exit_preset", length = 20)
	private ExitPreset exitPreset;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PracticeRiskSnapshot(
		PracticeAttempt attempt,
		long runNumber,
		Trade buyTrade,
		BigDecimal entryPrice,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		LocalDateTime createdAt) {
		this.attempt = attempt;
		this.runNumber = runNumber;
		this.buyTrade = buyTrade;
		this.entryPrice = entryPrice;
		this.stopLossPrice = stopLossPrice;
		this.takeProfitPrice = takeProfitPrice;
		this.createdAt = createdAt;
	}

	public static PracticeRiskSnapshot create(
		PracticeAttempt attempt,
		long runNumber,
		Trade buyTrade,
		BigDecimal entryPrice,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		LocalDateTime createdAt) {
		return new PracticeRiskSnapshot(
			attempt, runNumber, buyTrade, entryPrice, stopLossPrice, takeProfitPrice, createdAt);
	}
}
