// 계좌·종목별 현재 보유수량과 평균단가를 영속하는 엔티티
package com.finplay.api.portfolio.domain;

import com.finplay.api.account.domain.Account;
import com.finplay.api.market.domain.Instrument;
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
@Table(name = "holdings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(nullable = false, precision = 30, scale = 8)
	private BigDecimal quantity;

	@Column(name = "average_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal averagePrice;

	@Column(name = "is_active", nullable = false)
	private boolean isActive;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private Holding(Account account, Instrument instrument, LocalDateTime now) {
		this.account = account;
		this.instrument = instrument;
		this.quantity = BigDecimal.ZERO;
		this.averagePrice = BigDecimal.ZERO;
		this.isActive = false;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Holding create(Account account, Instrument instrument, LocalDateTime now) {
		return new Holding(account, instrument, now);
	}
}
