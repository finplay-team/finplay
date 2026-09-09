package com.finplay.api.domain.market.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "instruments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Instrument {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Market market;

	@Column(nullable = false, length = 20)
	private String symbol;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "tick_size", nullable = false, precision = 18, scale = 8)
	private BigDecimal tickSize;

	@Column(name = "min_order_amount", nullable = false)
	private long minOrderAmount;

	@Column(nullable = false)
	private boolean tradable;

	@Column(name = "is_tutorial_sample", nullable = false)
	private boolean tutorialSample;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private Instrument(
		Market market,
		String symbol,
		String name,
		BigDecimal tickSize,
		long minOrderAmount,
		boolean tradable,
		boolean tutorialSample,
		LocalDateTime createdAt) {
		this.market = market;
		this.symbol = symbol;
		this.name = name;
		this.tickSize = tickSize;
		this.minOrderAmount = minOrderAmount;
		this.tradable = tradable;
		this.tutorialSample = tutorialSample;
		this.createdAt = createdAt;
	}

	public static Instrument create(
		Market market,
		String symbol,
		String name,
		BigDecimal tickSize,
		long minOrderAmount,
		boolean tradable,
		LocalDateTime now) {
		return new Instrument(market, symbol, name, tickSize, minOrderAmount, tradable, false, now);
	}
}
