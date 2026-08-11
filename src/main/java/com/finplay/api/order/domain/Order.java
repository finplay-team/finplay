// 회원의 매수/매도 주문 요청과 멱등성 정보를 영속하는 엔티티
package com.finplay.api.order.domain;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
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
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private OrderSide side;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false, length = 10)
	private OrderType orderType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private OrderStatus status;

	@Column(nullable = false, precision = 30, scale = 8)
	private BigDecimal quantity;

	@Column(name = "limit_price", precision = 18, scale = 8)
	private BigDecimal limitPrice;

	// null이면 실제 가격 주문, non-null이면 교육 전용 가상 가격 세션에 귀속된 주문이다(030 COIN-PRICE-RUNTIME-006).
	@Column(name = "practice_price_session_id")
	private Long practicePriceSessionId;

	@Column(name = "idempotency_key", nullable = false, length = 100)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
	private String requestHash;

	@Column(name = "requested_at", nullable = false)
	private LocalDateTime requestedAt;

	private Order(
		User user,
		Account account,
		Instrument instrument,
		OrderSide side,
		OrderType orderType,
		OrderStatus status,
		BigDecimal quantity,
		BigDecimal limitPrice,
		Long practicePriceSessionId,
		String idempotencyKey,
		String requestHash,
		LocalDateTime requestedAt) {
		this.user = user;
		this.account = account;
		this.instrument = instrument;
		this.side = side;
		this.orderType = orderType;
		this.status = status;
		this.quantity = quantity;
		this.limitPrice = limitPrice;
		this.practicePriceSessionId = practicePriceSessionId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.requestedAt = requestedAt;
	}

	public static Order create(
		User user,
		Account account,
		Instrument instrument,
		OrderSide side,
		OrderType orderType,
		BigDecimal quantity,
		String idempotencyKey,
		String requestHash,
		LocalDateTime requestedAt) {
		return new Order(
			user,
			account,
			instrument,
			side,
			orderType,
			OrderStatus.FILLED,
			quantity,
			null,
			null,
			idempotencyKey,
			requestHash,
			requestedAt);
	}

	public static Order createLimitPending(
		User user,
		Account account,
		Instrument instrument,
		OrderSide side,
		BigDecimal quantity,
		BigDecimal limitPrice,
		String idempotencyKey,
		String requestHash,
		LocalDateTime requestedAt) {
		return new Order(
			user,
			account,
			instrument,
			side,
			OrderType.LIMIT,
			OrderStatus.PENDING,
			quantity,
			limitPrice,
			null,
			idempotencyKey,
			requestHash,
			requestedAt);
	}

	// 교육 전용 지정가 BUY 생성 — side를 서버가 BUY로 고정하고 practicePriceSessionId를 기록한다
	// (030 COIN-PRICE-RUNTIME-006, PracticeLimitOrderCreationService 전용).
	public static Order createPracticeLimitPendingBuy(
		User user,
		Account account,
		Instrument instrument,
		BigDecimal quantity,
		BigDecimal limitPrice,
		Long practicePriceSessionId,
		String idempotencyKey,
		String requestHash,
		LocalDateTime requestedAt) {
		return new Order(
			user,
			account,
			instrument,
			OrderSide.BUY,
			OrderType.LIMIT,
			OrderStatus.PENDING,
			quantity,
			limitPrice,
			practicePriceSessionId,
			idempotencyKey,
			requestHash,
			requestedAt);
	}

	public void markFilled() {
		if (this.status != OrderStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 주문만 체결 확정할 수 있습니다.");
		}
		this.status = OrderStatus.FILLED;
	}

	public void cancel() {
		if (this.status != OrderStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 주문만 취소할 수 있습니다.");
		}
		this.status = OrderStatus.CANCELLED;
	}

	public void modify(BigDecimal quantity, BigDecimal limitPrice) {
		if (this.status != OrderStatus.PENDING) {
			throw new IllegalStateException("PENDING 상태의 주문만 수정할 수 있습니다.");
		}
		this.quantity = quantity;
		this.limitPrice = limitPrice;
	}
}
