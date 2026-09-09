package com.finplay.api.domain.portfolio.entity;

import com.finplay.api.domain.order.entity.Trade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "holding_lots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HoldingLot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "holding_id", nullable = false)
	private Holding holding;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buy_trade_id", nullable = false)
	private Trade buyTrade;

	@Column(name = "original_quantity", nullable = false, precision = 30, scale = 8)
	private BigDecimal originalQuantity;

	@Column(name = "remaining_quantity", nullable = false, precision = 30, scale = 8)
	private BigDecimal remainingQuantity;

	@Column(name = "unit_cost", nullable = false, precision = 18, scale = 8)
	private BigDecimal unitCost;

	@Column(name = "buy_fee", nullable = false)
	private long buyFee;

	@Column(name = "executed_at", nullable = false)
	private LocalDateTime executedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private HoldingLot(
		Holding holding,
		Trade buyTrade,
		BigDecimal quantity,
		BigDecimal unitCost,
		long buyFee,
		LocalDateTime executedAt,
		LocalDateTime createdAt) {
		this.holding = holding;
		this.buyTrade = buyTrade;
		this.originalQuantity = quantity;
		this.remainingQuantity = quantity;
		this.unitCost = unitCost;
		this.buyFee = buyFee;
		this.executedAt = executedAt;
		this.createdAt = createdAt;
	}

	public static HoldingLot create(
		Holding holding,
		Trade buyTrade,
		BigDecimal quantity,
		BigDecimal unitCost,
		long buyFee,
		LocalDateTime executedAt,
		LocalDateTime now) {
		return new HoldingLot(holding, buyTrade, quantity, unitCost, buyFee, executedAt, now);
	}

	public void consume(BigDecimal quantity) {
		BigDecimal newRemaining = this.remainingQuantity.subtract(quantity);
		if (newRemaining.signum() < 0) {
			throw new IllegalStateException("잔여수량보다 큰 수량을 소비할 수 없습니다.");
		}
		this.remainingQuantity = newRemaining;
	}
}
