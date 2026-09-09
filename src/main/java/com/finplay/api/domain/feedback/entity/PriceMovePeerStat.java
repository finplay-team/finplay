package com.finplay.api.domain.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "price_move_peer_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceMovePeerStat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "price_move_event_id", nullable = false)
	private PriceMoveEvent priceMoveEvent;

	@Column(name = "service_date", nullable = false)
	private LocalDate serviceDate;

	@Column(name = "holder_count", nullable = false)
	private int holderCount;

	@Column(name = "sold_within_30min_count", nullable = false)
	private int soldWithin30MinCount;

	@Column(name = "median_minutes_to_sell")
	private Integer medianMinutesToSell;

	@Column(name = "aggregated_at", nullable = false)
	private LocalDateTime aggregatedAt;

	private PriceMovePeerStat(
		PriceMoveEvent priceMoveEvent,
		LocalDate serviceDate,
		int holderCount,
		int soldWithin30MinCount,
		Integer medianMinutesToSell,
		LocalDateTime aggregatedAt) {
		this.priceMoveEvent = priceMoveEvent;
		this.serviceDate = serviceDate;
		this.holderCount = holderCount;
		this.soldWithin30MinCount = soldWithin30MinCount;
		this.medianMinutesToSell = medianMinutesToSell;
		this.aggregatedAt = aggregatedAt;
	}

	public static PriceMovePeerStat create(
		PriceMoveEvent priceMoveEvent,
		LocalDate serviceDate,
		int holderCount,
		int soldWithin30MinCount,
		Integer medianMinutesToSell,
		LocalDateTime aggregatedAt) {
		return new PriceMovePeerStat(
			priceMoveEvent, serviceDate, holderCount, soldWithin30MinCount, medianMinutesToSell, aggregatedAt);
	}
}
