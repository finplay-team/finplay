// OCO 예약을 구성하는 손절·익절 개별 조건과 그 실행 가격선을 영속하는 엔티티
package com.finplay.api.order.domain;

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
@Table(name = "exit_plan_conditions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExitPlanCondition {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "exit_plan_id", nullable = false)
	private ExitPlan exitPlan;

	@Enumerated(EnumType.STRING)
	@Column(name = "condition_type", nullable = false, length = 20)
	private ExitPlanConditionType conditionType;

	@Column(name = "trigger_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal triggerPrice;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ExitPlanConditionStatus status;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private ExitPlanCondition(
		ExitPlan exitPlan, ExitPlanConditionType conditionType, BigDecimal triggerPrice, LocalDateTime createdAt) {
		this.exitPlan = exitPlan;
		this.conditionType = conditionType;
		this.triggerPrice = triggerPrice;
		this.status = ExitPlanConditionStatus.PENDING;
		this.createdAt = createdAt;
	}

	public static ExitPlanCondition create(
		ExitPlan exitPlan, ExitPlanConditionType conditionType, BigDecimal triggerPrice, LocalDateTime now) {
		return new ExitPlanCondition(exitPlan, conditionType, triggerPrice, now);
	}

	// 소속 exit plan이 사용자 취소로 종결될 때 PENDING 조건을 함께 종결한다(021 plan.md, 정확히 한 번 규칙).
	public void cancel() {
		if (this.status != ExitPlanConditionStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 조건만 취소할 수 있습니다.");
		}
		this.status = ExitPlanConditionStatus.CANCELLED;
	}

	// 가격 트리거로 실제 체결된 조건(익절 또는 손절 중 하나)을 TRIGGERED로 전이한다(021 plan.md "가격 트리거").
	public void trigger() {
		if (this.status != ExitPlanConditionStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 조건만 트리거할 수 있습니다.");
		}
		this.status = ExitPlanConditionStatus.TRIGGERED;
	}

	// 반대쪽 조건을 OCO 규칙으로 자동 취소한다 — 사용자 취소(cancel(), CANCELLED)와 상태를 구분해 원인을 남긴다.
	public void cancelByOco() {
		if (this.status != ExitPlanConditionStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 조건만 취소할 수 있습니다.");
		}
		this.status = ExitPlanConditionStatus.CANCELLED_BY_OCO;
	}
}
