// StockCandleAggregator의 버킷 키·OHLCV 산출·경계 규칙을 검증하는 순수 단위 테스트(이슈 #143)
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockCandleAggregatorTest {

	private static StockCandleDto minuteCandle(
		LocalDate tradingDate, LocalTime candleTime, long open, long high, long low, long close, long volume) {
		return new StockCandleDto(
			tradingDate,
			candleTime,
			BigDecimal.valueOf(open),
			BigDecimal.valueOf(high),
			BigDecimal.valueOf(low),
			BigDecimal.valueOf(close),
			volume);
	}

	@Test
	@DisplayName("interval=1m을 넘기면 IllegalArgumentException을 던진다")
	void aggregateThrowsWhenIntervalIsOneMinute() {
		List<StockCandleDto> minuteCandles = List
			.of(minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(9, 0), 100, 105, 99, 102, 10));

		assertThatThrownBy(() -> StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_MINUTE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("분봉이 0개면 빈 목록을 즉시 반환한다")
	void aggregateReturnsEmptyListWhenNoMinuteCandles() {
		assertThat(StockCandleAggregator.aggregate(List.of(), CandleInterval.ONE_DAY)).isEmpty();
	}

	@Test
	@DisplayName("1d 버킷 키는 tradingDate 그대로다")
	void dailyBucketKeyIsTradingDate() {
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(9, 1), 102, 110, 101, 108, 20),
			minuteCandle(LocalDate.of(2024, 1, 16), LocalTime.of(9, 0), 200, 205, 199, 203, 30));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_DAY);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 15));
		assertThat(result.get(1).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 16));
	}

	@Test
	@DisplayName("같은 주에 속한 여러 거래일의 분봉이 하나의 1w 버킷(그 주 월요일)으로 묶인다")
	void weeklyBucketMergesMultipleTradingDatesInSameWeek() {
		// 2024-01-15(월)~01-19(금)은 같은 ISO 주.
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 1, 17), LocalTime.of(9, 0), 150, 160, 145, 155, 20),
			minuteCandle(LocalDate.of(2024, 1, 19), LocalTime.of(9, 0), 200, 210, 190, 205, 30));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_WEEK);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 15));
	}

	@Test
	@DisplayName("1w 버킷의 시작은 토요일·일요일이 아니라 그 주의 월요일이다")
	void weekBucketStartsOnMondayNotSaturdayOrSunday() {
		// 2024-01-13(토)·01-14(일)이 속한 주의 월요일은 2024-01-08.
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 13), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 1, 14), LocalTime.of(9, 0), 110, 115, 109, 112, 20));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_WEEK);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 8));
	}

	@Test
	@DisplayName("같은 달에 속한 여러 거래일의 분봉이 하나의 1M 버킷(그 달 1일)으로 묶인다")
	void monthlyBucketMergesMultipleTradingDatesInSameMonth() {
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 5), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 1, 25), LocalTime.of(9, 0), 200, 210, 190, 205, 30));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_MONTH);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 1));
	}

	@Test
	@DisplayName("같은 ISO 주에 속하지만 서로 다른 달인 거래일은 1w에서는 하나로, 1M에서는 별개 버킷으로 갈린다")
	void weekBoundaryCanSpanTwoMonthsWhileMonthBoundarySeparatesThem() {
		// 2024-01-29(월)~02-02(금)은 같은 ISO 주지만 1월·2월에 걸쳐 있다.
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 29), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 2, 2), LocalTime.of(9, 0), 200, 210, 190, 205, 30));

		List<StockCandleDto> weeklyResult = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_WEEK);
		List<StockCandleDto> monthlyResult = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_MONTH);

		assertThat(weeklyResult).hasSize(1);
		assertThat(weeklyResult.get(0).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 29));

		assertThat(monthlyResult).hasSize(2);
		assertThat(monthlyResult.get(0).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 1));
		assertThat(monthlyResult.get(1).tradingDate()).isEqualTo(LocalDate.of(2024, 2, 1));
	}

	@Test
	@DisplayName("월 말일에서 다음 달로 넘어가는 거래일들은 서로 다른 1M 버킷으로 나뉜다")
	void monthBoundarySeparatesLastDayOfMonthFromFirstDayOfNextMonth() {
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 31), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 2, 1), LocalTime.of(9, 0), 200, 210, 190, 205, 30));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_MONTH);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).tradingDate()).isEqualTo(LocalDate.of(2024, 1, 1));
		assertThat(result.get(1).tradingDate()).isEqualTo(LocalDate.of(2024, 2, 1));
	}

	@Test
	@DisplayName("OHLCV는 최초 open·최대 high·최소 low·최종 close·합계 volume으로 산출된다")
	void ohlcvIsComputedFromFirstOpenMaxHighMinLowLastCloseSumVolume() {
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(9, 1), 102, 110, 101, 108, 20),
			minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(9, 2), 108, 109, 95, 107, 15));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_DAY);

		assertThat(result).hasSize(1);
		StockCandleDto bucket = result.get(0);
		assertThat(bucket.open()).isEqualByComparingTo(BigDecimal.valueOf(100));
		assertThat(bucket.high()).isEqualByComparingTo(BigDecimal.valueOf(110));
		assertThat(bucket.low()).isEqualByComparingTo(BigDecimal.valueOf(95));
		assertThat(bucket.close()).isEqualByComparingTo(BigDecimal.valueOf(107));
		assertThat(bucket.volume()).isEqualTo(45L);
	}

	@Test
	@DisplayName("거래일이 없는 주는 결과에 나타나지 않는다")
	void weeksWithNoTradingDayAreAbsentFromResult() {
		// 1주차(01-08 월요일 버킷), 2주차(01-15) 건너뛰고 3주차(01-22 월요일 버킷)만 데이터 존재.
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 9), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 1, 23), LocalTime.of(9, 0), 200, 210, 190, 205, 30));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_WEEK);

		assertThat(result).hasSize(2);
		assertThat(result).extracting(StockCandleDto::tradingDate)
			.containsExactly(LocalDate.of(2024, 1, 8), LocalDate.of(2024, 1, 22));
		assertThat(result).extracting(StockCandleDto::tradingDate).doesNotContain(LocalDate.of(2024, 1, 15));
	}

	@Test
	@DisplayName("거래일이 없는 달은 결과에 나타나지 않는다")
	void monthsWithNoTradingDayAreAbsentFromResult() {
		// 1월·3월에만 데이터, 2월은 건너뜀.
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 10), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 3, 10), LocalTime.of(9, 0), 200, 210, 190, 205, 30));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_MONTH);

		assertThat(result).hasSize(2);
		assertThat(result).extracting(StockCandleDto::tradingDate)
			.containsExactly(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1));
		assertThat(result).extracting(StockCandleDto::tradingDate).doesNotContain(LocalDate.of(2024, 2, 1));
	}

	@Test
	@DisplayName("sourceTime에 해당하는 candleTime은 항상 버킷 시작일의 자정(LocalTime.MIDNIGHT)이다")
	void candleTimeIsAlwaysMidnightOfBucketStart() {
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 15), LocalTime.of(14, 37), 100, 105, 99, 102, 10));

		List<StockCandleDto> dailyResult = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_DAY);
		List<StockCandleDto> weeklyResult = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_WEEK);
		List<StockCandleDto> monthlyResult = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_MONTH);

		assertThat(dailyResult.get(0).candleTime()).isEqualTo(LocalTime.MIDNIGHT);
		assertThat(weeklyResult.get(0).candleTime()).isEqualTo(LocalTime.MIDNIGHT);
		assertThat(monthlyResult.get(0).candleTime()).isEqualTo(LocalTime.MIDNIGHT);
	}

	@Test
	@DisplayName("여러 버킷이 있으면 결과는 버킷 시작일 오름차순으로 반환된다")
	void resultIsOrderedAscendingByBucketStart() {
		List<StockCandleDto> minuteCandles = List.of(
			minuteCandle(LocalDate.of(2024, 1, 5), LocalTime.of(9, 0), 100, 105, 99, 102, 10),
			minuteCandle(LocalDate.of(2024, 2, 5), LocalTime.of(9, 0), 200, 210, 190, 205, 30),
			minuteCandle(LocalDate.of(2024, 3, 5), LocalTime.of(9, 0), 300, 310, 290, 305, 40));

		List<StockCandleDto> result = StockCandleAggregator.aggregate(minuteCandles, CandleInterval.ONE_MONTH);

		assertThat(result).extracting(StockCandleDto::tradingDate)
			.containsExactly(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1));
	}
}
