// OCO 예약 생성 요청의 Idempotency-Key와 request hash를 최초 결과 plan에 매핑해 영속하는 엔티티
package com.finplay.api.order.domain;

import com.finplay.api.auth.domain.User;
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

	// 항상 UUID.toString() lowercase canonical 값을 저장한다 (016 plan).
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
