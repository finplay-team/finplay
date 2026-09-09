package com.finplay.api.domain.feedback.entity;

import com.finplay.api.domain.market.entity.Instrument;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "price_move_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceMoveEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Market market;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 20)
	private PriceMoveEventType eventType;

	@Column(name = "origin_trade_date", nullable = false)
	private LocalDate originTradeDate;

	@Column(name = "window_start")
	private LocalTime windowStart;

	@Column(name = "window_end")
	private LocalTime windowEnd;

	@Column(name = "occurred_at")
	private LocalDateTime occurredAt;

	@Column(name = "change_rate", nullable = false, precision = 10, scale = 6)
	private BigDecimal changeRate;

	@Column(name = "detection_score", nullable = false, precision = 10, scale = 4)
	private BigDecimal detectionScore;

	@Column(columnDefinition = "TEXT")
	private String narrative;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "reveal_time")
	private LocalTime revealTime;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PriceMoveEvent(
		Instrument instrument,
		Market market,
		PriceMoveEventType eventType,
		LocalDate originTradeDate,
		LocalTime windowStart,
		LocalTime windowEnd,
		LocalDateTime occurredAt,
		BigDecimal changeRate,
		BigDecimal detectionScore,
		String narrative,
		NarrativeSource narrativeSource,
		LocalTime revealTime,
		LocalDateTime createdAt) {
		this.instrument = instrument;
		this.market = market;
		this.eventType = eventType;
		this.originTradeDate = originTradeDate;
		this.windowStart = windowStart;
		this.windowEnd = windowEnd;
		this.occurredAt = occurredAt;
		this.changeRate = changeRate;
		this.detectionScore = detectionScore;
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.revealTime = revealTime;
		this.createdAt = createdAt;
	}

	public static PriceMoveEvent createStock(
		Instrument instrument,
		PriceMoveEventType eventType,
		LocalDate originTradeDate,
		LocalTime windowStart,
		LocalTime windowEnd,
		BigDecimal changeRate,
		BigDecimal detectionScore,
		String narrative,
		NarrativeSource narrativeSource,
		LocalTime revealTime,
		LocalDateTime now) {
		requireMarket(instrument, Market.STOCK);
		return new PriceMoveEvent(
			instrument,
			Market.STOCK,
			eventType,
			originTradeDate,
			windowStart,
			windowEnd,
			null,
			changeRate,
			detectionScore,
			narrative,
			narrativeSource,
			revealTime,
			now);
	}

	public static PriceMoveEvent createCrypto(
		Instrument instrument,
		LocalDateTime occurredAt,
		BigDecimal changeRate,
		BigDecimal detectionScore,
		String narrative,
		NarrativeSource narrativeSource,
		LocalDateTime now) {
		requireMarket(instrument, Market.CRYPTO);
		return new PriceMoveEvent(
			instrument,
			Market.CRYPTO,
			PriceMoveEventType.INTRADAY,
			occurredAt.toLocalDate(),
			null,
			null,
			occurredAt,
			changeRate,
			detectionScore,
			narrative,
			narrativeSource,
			null,
			now);
	}

	private static void requireMarket(Instrument instrument, Market expected) {
		if (instrument.getMarket() != expected) {
			throw new IllegalArgumentException(
				"종목의 시장(" + instrument.getMarket() + ")이 카드 형태(" + expected + ")와 다릅니다.");
		}
	}
}
