// 주문의 체결 결과를 영속하는 불변 원장 엔티티
package com.finplay.api.order.domain;

import com.finplay.api.account.domain.Account;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.StockReplaySession;
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
@Table(name = "trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "stock_replay_session_id")
	private StockReplaySession stockReplaySession;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private OrderSide side;

	@Column(nullable = false, precision = 18, scale = 8)
	private BigDecimal price;

	@Column(nullable = false, precision = 30, scale = 8)
	private BigDecimal quantity;

	@Column(nullable = false)
	private long amount;

	@Column(nullable = false)
	private long fee;

	@Column(name = "realized_pnl")
	private Long realizedPnl;

	@Column(name = "executed_at", nullable = false)
	private LocalDateTime executedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private Trade(
		Order order,
		Account account,
		Instrument instrument,
		StockReplaySession stockReplaySession,
		OrderSide side,
		BigDecimal price,
		BigDecimal quantity,
		long amount,
		long fee,
		Long realizedPnl,
		LocalDateTime executedAt,
		LocalDateTime createdAt) {
		this.order = order;
		this.account = account;
		this.instrument = instrument;
		this.stockReplaySession = stockReplaySession;
		this.side = side;
		this.price = price;
		this.quantity = quantity;
		this.amount = amount;
		this.fee = fee;
		this.realizedPnl = realizedPnl;
		this.executedAt = executedAt;
		this.createdAt = createdAt;
	}

	public static Trade of(
		Order order,
		Account account,
		Instrument instrument,
		OrderSide side,
		BigDecimal price,
		BigDecimal quantity,
		long amount,
		long fee,
		Long realizedPnl,
		LocalDateTime executedAt,
		LocalDateTime now) {
		return of(
			order, account, instrument, null, side, price, quantity, amount, fee, realizedPnl, executedAt, now);
	}

	public static Trade of(
		Order order,
		Account account,
		Instrument instrument,
		StockReplaySession stockReplaySession,
		OrderSide side,
		BigDecimal price,
		BigDecimal quantity,
		long amount,
		long fee,
		Long realizedPnl,
		LocalDateTime executedAt,
		LocalDateTime now) {
		return new Trade(
			order, account, instrument, stockReplaySession, side, price, quantity, amount, fee, realizedPnl, executedAt,
			now);
	}

	public void fillRealizedPnl(long realizedPnl) {
		if (this.realizedPnl != null) {
			throw new IllegalStateException("실현손익은 이미 채워져 있어 다시 채울 수 없습니다.");
		}
		this.realizedPnl = realizedPnl;
	}
}
