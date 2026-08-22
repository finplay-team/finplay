// 매도 체결이 FIFO로 소비한 매수 lot별 배분 내역을 영속하는 엔티티
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
@Table(name = "trade_allocations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeAllocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sell_trade_id", nullable = false)
	private Trade sellTrade;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "holding_lot_id", nullable = false)
	private HoldingLot holdingLot;

	@Column(name = "allocated_quantity", nullable = false, precision = 30, scale = 8)
	private BigDecimal allocatedQuantity;

	@Column(name = "allocated_cost", nullable = false)
	private long allocatedCost;

	@Column(name = "allocated_buy_fee", nullable = false)
	private long allocatedBuyFee;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private TradeAllocation(
		Trade sellTrade,
		HoldingLot holdingLot,
		BigDecimal allocatedQuantity,
		long allocatedCost,
		long allocatedBuyFee,
		LocalDateTime createdAt) {
		this.sellTrade = sellTrade;
		this.holdingLot = holdingLot;
		this.allocatedQuantity = allocatedQuantity;
		this.allocatedCost = allocatedCost;
		this.allocatedBuyFee = allocatedBuyFee;
		this.createdAt = createdAt;
	}

	public static TradeAllocation create(
		Trade sellTrade,
		HoldingLot holdingLot,
		BigDecimal allocatedQuantity,
		long allocatedCost,
		long allocatedBuyFee,
		LocalDateTime now) {
		return new TradeAllocation(sellTrade, holdingLot, allocatedQuantity, allocatedCost, allocatedBuyFee, now);
	}
}
