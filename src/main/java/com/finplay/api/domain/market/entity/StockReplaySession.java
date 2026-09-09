package com.finplay.api.domain.market.entity;

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

	public static StockReplaySession preparing(
		LocalDate serviceDate, LocalDate sourceTradingDate, LocalDateTime createdAt) {
		return new StockReplaySession(
			serviceDate, sourceTradingDate, PreparationStatus.PREPARING, null, null, createdAt);
	}

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

	public void resolveReady(LocalDate sourceTradingDate, LocalDateTime resolvedAt) {
		if (this.preparationStatus != PreparationStatus.PREPARING) {
			throw new IllegalStateException("PREPARING 상태에서만 READY로 전환할 수 있습니다.");
		}
		if (sourceTradingDate == null) {
			throw new IllegalArgumentException("READY 상태에서는 source_trading_date가 필수입니다.");
		}
		if (resolvedAt == null) {
			throw new IllegalArgumentException("READY 상태에서는 resolved_at이 필수입니다.");
		}
		this.sourceTradingDate = sourceTradingDate;
		this.preparationStatus = PreparationStatus.READY;
		this.resolvedAt = resolvedAt;
	}

	public void resolveFailed(LocalDate sourceTradingDate, LocalDateTime resolvedAt, String failureReason) {
		if (this.preparationStatus != PreparationStatus.PREPARING) {
			throw new IllegalStateException("PREPARING 상태에서만 FAILED로 전환할 수 있습니다.");
		}
		if (resolvedAt == null) {
			throw new IllegalArgumentException("FAILED 상태에서는 resolved_at이 필수입니다.");
		}
		if (failureReason == null || failureReason.isBlank()) {
			throw new IllegalArgumentException("FAILED 상태에서는 failure_reason이 필수입니다.");
		}
		this.sourceTradingDate = sourceTradingDate;
		this.preparationStatus = PreparationStatus.FAILED;
		this.resolvedAt = resolvedAt;
		this.failureReason = failureReason;
	}
}
