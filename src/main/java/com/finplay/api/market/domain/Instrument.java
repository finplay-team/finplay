// 시장별 거래 종목의 심볼·호가단위·최소주문금액·거래가능 여부를 표현하는 엔티티
package com.finplay.api.market.domain;

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

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private Instrument(
		Market market,
		String symbol,
		String name,
		BigDecimal tickSize,
		long minOrderAmount,
		boolean tradable,
		LocalDateTime createdAt) {
		this.market = market;
		this.symbol = symbol;
		this.name = name;
		this.tickSize = tickSize;
		this.minOrderAmount = minOrderAmount;
		this.tradable = tradable;
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
		return new Instrument(market, symbol, name, tickSize, minOrderAmount, tradable, now);
	}
}
