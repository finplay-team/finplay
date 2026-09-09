package com.finplay.api.domain.education.marketpractice.entity;

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
@Table(name = "practice_market_observations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeMarketObservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "holding_id", nullable = false)
	private Holding holding;

	@Column(name = "instrument_id", nullable = false)
	private Long instrumentId;

	@Column(name = "current_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal currentPrice;

	@Column(name = "closer_to_boundary")
	private Boolean closerToBoundary;

	@Enumerated(EnumType.STRING)
	@Column(name = "closer_boundary", length = 16)
	private PracticeBoundary closerBoundary;

	@Enumerated(EnumType.STRING)
	@Column(name = "evidence_type", length = 20)
	private PracticeEvidenceType evidenceType;

	@Column(name = "observed_at", nullable = false)
	private LocalDateTime observedAt;

	private PracticeMarketObservation(
		Long userId,
		Holding holding,
		Long instrumentId,
		BigDecimal currentPrice,
		Boolean closerToBoundary,
		PracticeBoundary closerBoundary,
		PracticeEvidenceType evidenceType,
		LocalDateTime observedAt) {
		this.userId = userId;
		this.holding = holding;
		this.instrumentId = instrumentId;
		this.currentPrice = currentPrice;
		this.closerToBoundary = closerToBoundary;
		this.closerBoundary = closerBoundary;
		this.evidenceType = evidenceType;
		this.observedAt = observedAt;
	}

	public static PracticeMarketObservation create(
		Long userId,
		Holding holding,
		Long instrumentId,
		BigDecimal currentPrice,
		Boolean closerToBoundary,
		PracticeBoundary closerBoundary,
		PracticeEvidenceType evidenceType,
		LocalDateTime observedAt) {
		return new PracticeMarketObservation(
			userId, holding, instrumentId, currentPrice, closerToBoundary, closerBoundary, evidenceType, observedAt);
	}
}
