package com.finplay.api.domain.order.entity;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.portfolio.entity.Holding;
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
@Table(name = "exit_plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExitPlan {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "holding_id", nullable = false)
	private Holding holding;

	@Column(name = "intention_id")
	private Long intentionId;

	@Column(name = "intention_instance_key", length = 36, columnDefinition = "CHAR(36)")
	private String intentionInstanceKey;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "buy_trade_id")
	private Trade buyTrade;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(nullable = false, precision = 30, scale = 8)
	private BigDecimal quantity;

	@Column(name = "entry_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal entryPrice;

	@Enumerated(EnumType.STRING)
	@Column(name = "exit_price_type", nullable = false, length = 10)
	private ExitPriceType exitPriceType;

	@Column(name = "stop_loss_rate", precision = 7, scale = 4)
	private BigDecimal stopLossRate;

	@Column(name = "take_profit_rate", precision = 8, scale = 4)
	private BigDecimal takeProfitRate;

	@Column(name = "stop_loss_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal stopLossPrice;

	@Column(name = "take_profit_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal takeProfitPrice;

	@Column(name = "baseline_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal baselinePrice;

	@Column(name = "baseline_observed_at", nullable = false)
	private LocalDateTime baselineObservedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ExitPlanStatus status;

	@Column(name = "reserved_at", nullable = false)
	private LocalDateTime reservedAt;

	@Column(name = "closed_at")
	private LocalDateTime closedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "triggered_order_id")
	private Order triggeredOrder;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "replay_session_id")
	private StockReplaySession replaySession;

	@Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
	private String requestHash;

	@Column(name = "practice_attempt_id")
	private Long practiceAttemptId;

	@Column(name = "practice_attempt_run_number")
	private Long practiceAttemptRunNumber;

	private ExitPlan(
		User user,
		Holding holding,
		Long intentionId,
		String intentionInstanceKey,
		Trade buyTrade,
		Instrument instrument,
		BigDecimal quantity,
		BigDecimal entryPrice,
		ExitPriceType exitPriceType,
		BigDecimal stopLossRate,
		BigDecimal takeProfitRate,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		BigDecimal baselinePrice,
		LocalDateTime baselineObservedAt,
		String requestHash,
		LocalDateTime reservedAt) {
		this.user = user;
		this.holding = holding;
		this.intentionId = intentionId;
		this.intentionInstanceKey = intentionInstanceKey;
		this.buyTrade = buyTrade;
		this.instrument = instrument;
		this.quantity = quantity;
		this.entryPrice = entryPrice;
		this.exitPriceType = exitPriceType;
		this.stopLossRate = stopLossRate;
		this.takeProfitRate = takeProfitRate;
		this.stopLossPrice = stopLossPrice;
		this.takeProfitPrice = takeProfitPrice;
		this.baselinePrice = baselinePrice;
		this.baselineObservedAt = baselineObservedAt;
		this.status = ExitPlanStatus.PENDING;
		this.reservedAt = reservedAt;
		this.requestHash = requestHash;
	}

	private static void validateRateSnapshot(
		ExitPriceType exitPriceType, BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		if (exitPriceType == ExitPriceType.PERCENT && (stopLossRate == null || takeProfitRate == null)) {
			throw new IllegalArgumentException("PERCENT 방식은 손절률·익절률 snapshot이 모두 필요합니다.");
		}
		if (exitPriceType == ExitPriceType.PRICE && (stopLossRate != null || takeProfitRate != null)) {
			throw new IllegalArgumentException("PRICE 방식은 손절률·익절률 snapshot을 가질 수 없습니다.");
		}
	}

	public static ExitPlan createGeneral(
		User user,
		Holding holding,
		Instrument instrument,
		BigDecimal quantity,
		BigDecimal entryPrice,
		ExitPriceType exitPriceType,
		BigDecimal stopLossRate,
		BigDecimal takeProfitRate,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		BigDecimal baselinePrice,
		LocalDateTime baselineObservedAt,
		String requestHash,
		LocalDateTime reservedAt) {
		validateRateSnapshot(exitPriceType, stopLossRate, takeProfitRate);
		return new ExitPlan(
			user,
			holding,
			null,
			null,
			null,
			instrument,
			quantity,
			entryPrice,
			exitPriceType,
			stopLossRate,
			takeProfitRate,
			stopLossPrice,
			takeProfitPrice,
			baselinePrice,
			baselineObservedAt,
			requestHash,
			reservedAt);
	}

	public static ExitPlan createPractice(
		User user,
		Holding holding,
		Instrument instrument,
		BigDecimal quantity,
		BigDecimal entryPrice,
		ExitPriceType exitPriceType,
		BigDecimal stopLossRate,
		BigDecimal takeProfitRate,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		BigDecimal baselinePrice,
		LocalDateTime baselineObservedAt,
		String requestHash,
		Long practiceAttemptId,
		Long practiceAttemptRunNumber,
		LocalDateTime reservedAt) {
		if (practiceAttemptId == null || practiceAttemptRunNumber == null || practiceAttemptRunNumber <= 0) {
			throw new IllegalArgumentException("튜토리얼 자동 예약은 attempt id와 양의 실행 세대 번호가 필요합니다.");
		}
		validateRateSnapshot(exitPriceType, stopLossRate, takeProfitRate);
		ExitPlan plan = new ExitPlan(
			user,
			holding,
			null,
			null,
			null,
			instrument,
			quantity,
			entryPrice,
			exitPriceType,
			stopLossRate,
			takeProfitRate,
			stopLossPrice,
			takeProfitPrice,
			baselinePrice,
			baselineObservedAt,
			requestHash,
			reservedAt);
		plan.practiceAttemptId = practiceAttemptId;
		plan.practiceAttemptRunNumber = practiceAttemptRunNumber;
		return plan;
	}

	public static ExitPlan createEducational(
		User user,
		Holding holding,
		Long intentionId,
		String intentionInstanceKey,
		Trade buyTrade,
		Instrument instrument,
		BigDecimal quantity,
		BigDecimal entryPrice,
		ExitPriceType exitPriceType,
		BigDecimal stopLossRate,
		BigDecimal takeProfitRate,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		BigDecimal baselinePrice,
		LocalDateTime baselineObservedAt,
		String requestHash,
		LocalDateTime reservedAt) {
		if (intentionId == null || intentionInstanceKey == null || buyTrade == null) {
			throw new IllegalArgumentException("교육 경로 OCO는 intentionId·intentionInstanceKey·buyTrade가 모두 필요합니다.");
		}
		validateRateSnapshot(exitPriceType, stopLossRate, takeProfitRate);
		return new ExitPlan(
			user,
			holding,
			intentionId,
			intentionInstanceKey,
			buyTrade,
			instrument,
			quantity,
			entryPrice,
			exitPriceType,
			stopLossRate,
			takeProfitRate,
			stopLossPrice,
			takeProfitPrice,
			baselinePrice,
			baselineObservedAt,
			requestHash,
			reservedAt);
	}

	public boolean isPending() {
		return this.status == ExitPlanStatus.PENDING;
	}

	public void cancel(LocalDateTime closedAt) {
		if (this.status != ExitPlanStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 예약만 취소할 수 있습니다.");
		}
		this.status = ExitPlanStatus.CANCELLED;
		this.closedAt = closedAt;
	}

	public void fillTakeProfit(Order triggeredOrder, LocalDateTime closedAt) {
		fill(ExitPlanStatus.FILLED_TAKE_PROFIT, triggeredOrder, closedAt);
	}

	public void fillStopLoss(Order triggeredOrder, LocalDateTime closedAt) {
		fill(ExitPlanStatus.FILLED_STOP_LOSS, triggeredOrder, closedAt);
	}

	private void fill(ExitPlanStatus filledStatus, Order triggeredOrder, LocalDateTime closedAt) {
		if (this.status != ExitPlanStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 예약만 체결할 수 있습니다.");
		}
		this.status = filledStatus;
		this.triggeredOrder = triggeredOrder;
		this.closedAt = closedAt;
	}
}
