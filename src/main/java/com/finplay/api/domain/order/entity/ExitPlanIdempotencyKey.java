package com.finplay.api.domain.order.entity;

import com.finplay.api.domain.auth.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "exit_plan_idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExitPlanIdempotencyKey {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "idempotency_key", nullable = false, length = 36)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
	private String requestHash;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "exit_plan_id", nullable = false)
	private ExitPlan exitPlan;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private ExitPlanIdempotencyKey(
		User user, String idempotencyKey, String requestHash, ExitPlan exitPlan, LocalDateTime createdAt) {
		this.user = user;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.exitPlan = exitPlan;
		this.createdAt = createdAt;
	}

	public static ExitPlanIdempotencyKey of(
		User user, String idempotencyKey, String requestHash, ExitPlan exitPlan, LocalDateTime now) {
		return new ExitPlanIdempotencyKey(user, idempotencyKey, requestHash, exitPlan, now);
	}
}
