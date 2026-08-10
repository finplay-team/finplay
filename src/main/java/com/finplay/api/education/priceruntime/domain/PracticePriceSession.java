// 사용자·종목별 코인 튜토리얼 가상 가격 세션(seed·진행 위치·현재가)을 표현하는 엔티티
package com.finplay.api.education.priceruntime.domain;

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

	// SMALLINT(V29 migration)와 타입을 맞춘다 — int로 두면 Hibernate ddl-auto: validate가 스키마 검증에서
	// 실패한다(SMALLINT vs INTEGER 불일치, ADR-0004).
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

	// 생성 직후 tick 0, currentPrice=startPrice, status=ACTIVE로 항상 유효한 상태로 만든다 (spec COIN-PRICE-RUNTIME-002).
	public static PracticePriceSession create(
		Long userId,
		Long instrumentId,
		long seed,
		short generatorVersion,
		BigDecimal startPrice,
		LocalDateTime createdAt) {
		return new PracticePriceSession(userId, instrumentId, seed, generatorVersion, startPrice, createdAt);
	}
}
