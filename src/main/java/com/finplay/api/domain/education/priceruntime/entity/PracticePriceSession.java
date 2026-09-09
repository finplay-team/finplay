package com.finplay.api.domain.education.priceruntime.entity;

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
@Table(name = "practice_price_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticePriceSession {

	private static final int MAX_TICK = 99;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "instrument_id", nullable = false)
	private Long instrumentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PracticePriceSessionStatus status;

	@Column(nullable = false)
	private long seed;

	@Column(name = "generator_version", nullable = false)
	private short generatorVersion;

	@Column(name = "start_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal startPrice;

	@Column(name = "current_tick", nullable = false)
	private short currentTick;

	@Column(name = "current_price", nullable = false, precision = 18, scale = 8)
	private BigDecimal currentPrice;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	private PracticePriceSession(
		Long userId,
		Long instrumentId,
		long seed,
		short generatorVersion,
		BigDecimal startPrice,
		LocalDateTime createdAt) {
		this.userId = userId;
		this.instrumentId = instrumentId;
		this.status = PracticePriceSessionStatus.ACTIVE;
		this.seed = seed;
		this.generatorVersion = generatorVersion;
		this.startPrice = startPrice;
		this.currentTick = 0;
		this.currentPrice = startPrice;
		this.createdAt = createdAt;
	}

	public static PracticePriceSession create(
		Long userId,
		Long instrumentId,
		long seed,
		short generatorVersion,
		BigDecimal startPrice,
		LocalDateTime createdAt) {
		return new PracticePriceSession(userId, instrumentId, seed, generatorVersion, startPrice, createdAt);
	}

	public void advance(int expectedTick, BigDecimal nextPrice) {
		if (this.status != PracticePriceSessionStatus.ACTIVE) {
			throw new IllegalStateException("ACTIVE 상태의 세션만 진행할 수 있습니다.");
		}
		if (expectedTick != this.currentTick + 1) {
			throw new IllegalStateException("expectedTick은 currentTick+1이어야 합니다.");
		}
		this.currentTick = (short)expectedTick;
		this.currentPrice = nextPrice;
	}

	public void complete(LocalDateTime now) {
		if (this.status != PracticePriceSessionStatus.ACTIVE) {
			throw new IllegalStateException("ACTIVE 상태의 세션만 완료할 수 있습니다.");
		}
		this.status = PracticePriceSessionStatus.COMPLETED;
		this.completedAt = now;
	}

	public static int maxTick() {
		return MAX_TICK;
	}
}
