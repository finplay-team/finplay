package com.finplay.api.domain.market.entity;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock_daily_candles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockDailyCandle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(name = "trading_date", nullable = false)
	private LocalDate tradingDate;

	@Column(nullable = false, precision = 18, scale = 4)
	private BigDecimal open;

	@Column(nullable = false, precision = 18, scale = 4)
	private BigDecimal high;

	@Column(nullable = false, precision = 18, scale = 4)
	private BigDecimal low;

	@Column(nullable = false, precision = 18, scale = 4)
	private BigDecimal close;

	@Column(nullable = false)
	private long volume;

	@Column(name = "data_source", nullable = false, length = 50)
	private String dataSource;

	@Column(name = "collected_at", nullable = false)
	private LocalDateTime collectedAt;

	private StockDailyCandle(
		Instrument instrument,
		LocalDate tradingDate,
		BigDecimal open,
		BigDecimal high,
		BigDecimal low,
		BigDecimal close,
		long volume,
		String dataSource,
		LocalDateTime collectedAt) {
		this.instrument = instrument;
		this.tradingDate = tradingDate;
		this.open = open;
		this.high = high;
		this.low = low;
		this.close = close;
		this.volume = volume;
		this.dataSource = dataSource;
		this.collectedAt = collectedAt;
	}

	public static StockDailyCandle create(
		Instrument instrument,
		LocalDate tradingDate,
		BigDecimal open,
		BigDecimal high,
		BigDecimal low,
		BigDecimal close,
		long volume,
		String dataSource,
		LocalDateTime collectedAt) {
		return new StockDailyCandle(instrument, tradingDate, open, high, low, close, volume, dataSource, collectedAt);
	}
}
