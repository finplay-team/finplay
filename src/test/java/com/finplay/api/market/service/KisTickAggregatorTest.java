// 체결 틱 → 1분 OHLCV 집계 로직(KisTickAggregator)의 단위 테스트 (tasks.md 15번째 줄, 이슈 #82)
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class KisTickAggregatorTest {

	private static final Long INSTRUMENT_ID = 1L;
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);

	private final KisTickAggregator aggregator = new KisTickAggregator();

	@Test
	void firstTickForInstrumentOpensBucketWithoutClosingAnyCandle() {
		Optional<StockCandleDto> closed = aggregator.onTick(
			INSTRUMENT_ID, tickTime(10, 0, 5), BigDecimal.valueOf(1000), 3);

		assertThat(closed).isEmpty();
		assertThat(aggregator.getClosedCandles(INSTRUMENT_ID, null, null)).isEmpty();
	}

	@Test
	void ticksWithinSameMinuteAccumulateOpenHighLowCloseVolume() {
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 0, 0), BigDecimal.valueOf(1000), 1);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 0, 20), BigDecimal.valueOf(1050), 2);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 0, 40), BigDecimal.valueOf(950), 3);
		Optional<StockCandleDto> closed = aggregator.onTick(
			INSTRUMENT_ID, tickTime(10, 0, 59), BigDecimal.valueOf(1020), 4);

		// 같은 분 안에서는 분봉이 아직 마감되지 않으므로 onTick은 계속 empty를 반환한다.
		assertThat(closed).isEmpty();
		assertThat(aggregator.getClosedCandles(INSTRUMENT_ID, null, null)).isEmpty();

		// 다음 분으로 넘어가는 틱이 와야 10:00 분봉이 마감되어 확정된다.
		Optional<StockCandleDto> closedOnBoundary = aggregator.onTick(
			INSTRUMENT_ID, tickTime(10, 1, 0), BigDecimal.valueOf(1010), 5);

		assertThat(closedOnBoundary).isPresent();
		StockCandleDto candle = closedOnBoundary.get();
		assertThat(candle.tradingDate()).isEqualTo(TRADING_DATE);
		assertThat(candle.candleTime()).isEqualTo(LocalTime.of(10, 0));
		assertThat(candle.open()).isEqualByComparingTo(BigDecimal.valueOf(1000));
		assertThat(candle.high()).isEqualByComparingTo(BigDecimal.valueOf(1050));
		assertThat(candle.low()).isEqualByComparingTo(BigDecimal.valueOf(950));
		assertThat(candle.close()).isEqualByComparingTo(BigDecimal.valueOf(1020));
		assertThat(candle.volume()).isEqualTo(10);
	}

	@Test
	void minuteBoundaryTransitionClosesPreviousBucketAndOpensNewOne() {
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 0, 0), BigDecimal.valueOf(1000), 1);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 1, 0), BigDecimal.valueOf(1010), 1);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 1, 30), BigDecimal.valueOf(1030), 2);
		// 10:02로 넘어가는 틱이 와야 10:01 분봉이 마감된다 — 아직 열려 있는 현재 분봉(10:02)은 노출하지 않는다.
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 2, 0), BigDecimal.valueOf(1040), 1);

		List<StockCandleDto> closedCandles = aggregator.getClosedCandles(INSTRUMENT_ID, null, null);

		assertThat(closedCandles).hasSize(2);
		assertThat(closedCandles.get(0).candleTime()).isEqualTo(LocalTime.of(10, 0));
		assertThat(closedCandles.get(1).candleTime()).isEqualTo(LocalTime.of(10, 1));
		assertThat(closedCandles.get(1).open()).isEqualByComparingTo(BigDecimal.valueOf(1010));
		assertThat(closedCandles.get(1).high()).isEqualByComparingTo(BigDecimal.valueOf(1030));
		assertThat(closedCandles.get(1).low()).isEqualByComparingTo(BigDecimal.valueOf(1010));
		assertThat(closedCandles.get(1).close()).isEqualByComparingTo(BigDecimal.valueOf(1030));
		assertThat(closedCandles.get(1).volume()).isEqualTo(3);
		// 10:02 분봉은 아직 열려 있으므로 확정 목록에 포함되지 않는다.
		assertThat(closedCandles)
			.extracting(StockCandleDto::candleTime)
			.doesNotContain(LocalTime.of(10, 2));
	}

	@Test
	void getClosedCandlesFiltersByFromToRange() {
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 0, 0), BigDecimal.valueOf(1000), 1);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 1, 0), BigDecimal.valueOf(1010), 1);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 2, 0), BigDecimal.valueOf(1020), 1);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 3, 0), BigDecimal.valueOf(1030), 1);

		List<StockCandleDto> filtered = aggregator.getClosedCandles(
			INSTRUMENT_ID, LocalDateTime.of(TRADING_DATE, LocalTime.of(10, 1)),
			LocalDateTime.of(TRADING_DATE, LocalTime.of(10, 1)));

		assertThat(filtered).hasSize(1);
		assertThat(filtered.get(0).candleTime()).isEqualTo(LocalTime.of(10, 1));
	}

	@Test
	void getClosedCandlesForDifferentInstrumentDoesNotSeeOtherInstrumentTicks() {
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 0, 0), BigDecimal.valueOf(1000), 1);
		aggregator.onTick(INSTRUMENT_ID, tickTime(10, 1, 0), BigDecimal.valueOf(1010), 1);

		assertThat(aggregator.getClosedCandles(999L, null, null)).isEmpty();
	}

	private static LocalDateTime tickTime(int hour, int minute, int second) {
		return LocalDateTime.of(TRADING_DATE, LocalTime.of(hour, minute, second));
	}
}
