package com.finplay.api.domain.account.entity;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tutorial_accounts", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "market"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TutorialAccount {

	private static final long INITIAL_CASH = 10_000_000L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Market market;

	@Column(name = "cash_balance", nullable = false)
	private long cashBalance;

	@Column(name = "reserved_cash", nullable = false)
	private long reservedCash;

	@Column(name = "realized_pnl", nullable = false)
	private long realizedPnl;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private TutorialAccount(User user, Market market, LocalDateTime now) {
		this.user = user;
		this.market = market;
		this.cashBalance = INITIAL_CASH;
		this.reservedCash = 0L;
		this.realizedPnl = 0L;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static TutorialAccount create(User user, Market market, LocalDateTime now) {
		return new TutorialAccount(user, market, now);
	}

	public void deductCash(long amount) {
		if (amount > this.cashBalance) {
			throw new IllegalStateException("튜토리얼 계좌 현금 잔고보다 큰 금액을 차감할 수 없습니다.");
		}
		this.cashBalance -= amount;
	}

	public void addCash(long amount) {
		this.cashBalance += amount;
	}

	public long getAvailableCash() {
		return this.cashBalance - this.reservedCash;
	}

	public void reserveCash(long amount) {
		if (amount > getAvailableCash()) {
			throw new IllegalStateException("튜토리얼 계좌에서 예약 가능한 현금보다 큰 금액을 예약할 수 없습니다.");
		}
		this.reservedCash += amount;
	}

	public void confirmReservedCash(long amount) {
		if (amount > this.reservedCash) {
			throw new IllegalStateException("튜토리얼 계좌에서 예약된 금액보다 큰 금액을 확정할 수 없습니다.");
		}
		this.reservedCash -= amount;
		this.cashBalance -= amount;
	}

	public void releaseReservedCash(long amount) {
		if (amount > this.reservedCash) {
			throw new IllegalStateException("튜토리얼 계좌에서 예약된 금액보다 큰 금액을 해제할 수 없습니다.");
		}
		this.reservedCash -= amount;
	}

	public void addRealizedPnl(long amount) {
		this.realizedPnl += amount;
	}

	public void reset(LocalDateTime now) {
		this.cashBalance = INITIAL_CASH;
		this.reservedCash = 0L;
		this.realizedPnl = 0L;
		this.updatedAt = now;
	}
}
