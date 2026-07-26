// 회원의 시장별 모의투자 잔고와 손익을 영속하는 계좌 엔티티
package com.finplay.api.account.domain;

import com.finplay.api.auth.domain.User;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

	private static final long INITIAL_SEED_MONEY = 10_000_000L;

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

	@Column(name = "seed_money", nullable = false)
	private long seedMoney;

	@Column(name = "realized_pnl", nullable = false)
	private long realizedPnl;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private Account(User user, Market market, LocalDateTime now) {
		this.user = user;
		this.market = market;
		this.cashBalance = INITIAL_SEED_MONEY;
		this.seedMoney = INITIAL_SEED_MONEY;
		this.realizedPnl = 0L;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Account create(User user, Market market, LocalDateTime now) {
		return new Account(user, market, now);
	}
}
