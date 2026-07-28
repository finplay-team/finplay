// 오늘 재생할 원본 거래일과 준비상태(PREPARING·READY·FAILED)를 표현하는 엔티티. OPEN·CLOSED는 저장하지 않는다.
package com.finplay.api.market.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock_replay_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReplaySession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "service_date", nullable = false)
	private LocalDate serviceDate;

	@Column(name = "source_trading_date")
	private LocalDate sourceTradingDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "preparation_status", nullable = false, length = 20)
	private PreparationStatus preparationStatus;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	@Column(name = "failure_reason", length = 255)
	private String failureReason;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private StockReplaySession(
		LocalDate serviceDate,
		LocalDate sourceTradingDate,
		PreparationStatus preparationStatus,
		LocalDateTime resolvedAt,
		String failureReason,
		LocalDateTime createdAt) {
		this.serviceDate = serviceDate;
		this.sourceTradingDate = sourceTradingDate;
		this.preparationStatus = preparationStatus;
		this.resolvedAt = resolvedAt;
		this.failureReason = failureReason;
		this.createdAt = createdAt;
	}

	// PREPARING: source_trading_date는 후보 거래일을 아직 고르지 못했으면 NULL, 검증 중이면 값을 가질 수 있다. resolved_at·failure_reason은 항상 NULL.
	public static StockReplaySession preparing(
		LocalDate serviceDate, LocalDate sourceTradingDate, LocalDateTime createdAt) {
		return new StockReplaySession(
			serviceDate, sourceTradingDate, PreparationStatus.PREPARING, null, null, createdAt);
	}

	// READY: source_trading_date·resolved_at는 필수, failure_reason은 항상 NULL.
	public static StockReplaySession ready(
		LocalDate serviceDate, LocalDate sourceTradingDate, LocalDateTime resolvedAt, LocalDateTime createdAt) {
		if (sourceTradingDate == null) {
			throw new IllegalArgumentException("READY 상태에서는 source_trading_date가 필수입니다.");
		}
		if (resolvedAt == null) {
			throw new IllegalArgumentException("READY 상태에서는 resolved_at이 필수입니다.");
		}
		return new StockReplaySession(
			serviceDate, sourceTradingDate, PreparationStatus.READY, resolvedAt, null, createdAt);
	}

	// FAILED: resolved_at·failure_reason은 필수. source_trading_date는 데이터를 찾지 못했으면 NULL, 특정 거래일 준비 중 실패했으면 값을 가질 수 있다 — 둘 다 허용.
	public static StockReplaySession failed(
		LocalDate serviceDate,
		LocalDate sourceTradingDate,
		LocalDateTime resolvedAt,
		String failureReason,
		LocalDateTime createdAt) {
		if (resolvedAt == null) {
			throw new IllegalArgumentException("FAILED 상태에서는 resolved_at이 필수입니다.");
		}
		if (failureReason == null || failureReason.isBlank()) {
			throw new IllegalArgumentException("FAILED 상태에서는 failure_reason이 필수입니다.");
		}
		return new StockReplaySession(
			serviceDate, sourceTradingDate, PreparationStatus.FAILED, resolvedAt, failureReason, createdAt);
	}
}
