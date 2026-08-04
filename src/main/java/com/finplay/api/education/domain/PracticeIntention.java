// 매수 전에 기록한 투자 실습의 수량과 손절·익절 의도를 표현하는 엔티티
package com.finplay.api.education.domain;

import com.finplay.api.auth.domain.User;
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
@Table(name = "practice_intentions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeIntention {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(nullable = false, precision = 30, scale = 8)
	private BigDecimal quantity;

	@Column(name = "stop_loss", nullable = false, precision = 18, scale = 8)
	private BigDecimal stopLoss;

	@Column(name = "take_profit", nullable = false, precision = 18, scale = 8)
	private BigDecimal takeProfit;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PracticeIntention(
		User user,
		Instrument instrument,
		BigDecimal quantity,
		BigDecimal stopLoss,
		BigDecimal takeProfit,
		LocalDateTime createdAt) {
		this.user = user;
		this.instrument = instrument;
		this.quantity = quantity;
		this.stopLoss = stopLoss;
		this.takeProfit = takeProfit;
		this.createdAt = createdAt;
	}

	public static PracticeIntention create(
		User user,
		Instrument instrument,
		BigDecimal quantity,
		BigDecimal stopLoss,
		BigDecimal takeProfit,
		LocalDateTime createdAt) {
		return new PracticeIntention(user, instrument, quantity, stopLoss, takeProfit, createdAt);
	}
}
