// 손절·익절을 한 쌍으로 묶은 OCO 청산 예약(일반·교육 경로 공통)을 영속하는 엔티티
package com.finplay.api.order.domain;

import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.portfolio.domain.Holding;
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

	// 두 경로 모두 항상 실제 holding에 결합된다 — 교육 경로도 chain에서 유도한 holding을 저장한다.
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "holding_id", nullable = false)
	private Holding holding;

	// 응답·evidence용 숫자 snapshot. FK·unique가 아니며 일반 경로는 항상 null이다.
	@Column(name = "intention_id")
	private Long intentionId;

	// 교육 경로만 사용하는 내부 UUID identity. 일반 경로는 항상 null이다.
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

	// 교육 경로는 buyTrade.entryPrice, 일반 경로는 생성 시점 holding.averagePrice snapshot이다.
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

	// 021은 코인만 다루므로 항상 null이다. 주식 확장 시 016이 이 FK를 다시 사용한다.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "replay_session_id")
	private StockReplaySession replaySession;

	@Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
	private String requestHash;

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

	// 019의 tagged union 불변식 — PERCENT만 원본 rate를 snapshot하고 PRICE는 rate 컬럼을 비운다.
	// 요청 필드 조합 검증(stopLoss·takeProfit 포함)은 호출부 정책이 400으로 먼저 걸러낸다.
	// 생성자가 아니라 정적 팩토리에서 호출한다 — 생성자에서 예외를 던지면 SpotBugs CT_CONSTRUCTOR_THROW가 잡는다
	// (Trade.of의 validateStockReplaySession과 같은 형태).
	private static void validateRateSnapshot(
		ExitPriceType exitPriceType, BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		if (exitPriceType == ExitPriceType.PERCENT && (stopLossRate == null || takeProfitRate == null)) {
			throw new IllegalArgumentException("PERCENT 방식은 손절률·익절률 snapshot이 모두 필요합니다.");
		}
		if (exitPriceType == ExitPriceType.PRICE && (stopLossRate != null || takeProfitRate != null)) {
			throw new IllegalArgumentException("PRICE 방식은 손절률·익절률 snapshot을 가질 수 없습니다.");
		}
	}

	// 일반 경로(intentionId 생략) 생성 — intentionId·intentionInstanceKey·buyTrade는 항상 null이다.
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

	// 교육 경로(intentionId 지정) 생성 — 016 chain 검증이 확정한 intention·매수 체결 snapshot을 함께 저장한다.
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

	// 사용자 취소(021 plan.md "잠금 순서" — holding을 먼저 잠근 뒤 이 plan을 잠그고 호출한다). 호출부(서비스 계층)가
	// 이미 PENDING 여부를 409로 검증한 뒤 부르므로, 여기서의 예외는 원장 불변식이 깨진 방어적 상황이다
	// (Order.cancel과 같은 형태).
	public void cancel(LocalDateTime closedAt) {
		if (this.status != ExitPlanStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 예약만 취소할 수 있습니다.");
		}
		this.status = ExitPlanStatus.CANCELLED;
		this.closedAt = closedAt;
	}
}
