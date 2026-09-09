package com.finplay.api.domain.order.entity;

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

	public void cancel() {
		if (this.status != ExitPlanConditionStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 조건만 취소할 수 있습니다.");
		}
		this.status = ExitPlanConditionStatus.CANCELLED;
	}

	public void trigger() {
		if (this.status != ExitPlanConditionStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 조건만 트리거할 수 있습니다.");
		}
		this.status = ExitPlanConditionStatus.TRIGGERED;
	}

	public void cancelByOco() {
		if (this.status != ExitPlanConditionStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 조건만 취소할 수 있습니다.");
		}
		this.status = ExitPlanConditionStatus.CANCELLED_BY_OCO;
	}
}
