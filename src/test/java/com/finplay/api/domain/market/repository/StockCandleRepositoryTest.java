package com.finplay.api.domain.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class StockCandleRepositoryTest {

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	private Instrument instrumentA;
	private Instrument instrumentB;

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 22);
	private static final LocalDate OTHER_TRADING_DATE = LocalDate.of(2026, 7, 23);

	@BeforeEach
	void setUp() {
		instrumentA = instrumentRepository.save(Instrument.create(
			Market.STOCK, "TEST001", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		instrumentB = instrumentRepository.save(Instrument.create(
			Market.STOCK, "TEST002", "테스트종목B", new BigDecimal("500"), 180000, true, LocalDateTime.now()));
	}

	private StockCandle newCandle(
		Instrument instrument, LocalDate tradingDate, LocalTime candleTime, String close) {
		return StockCandle.create(
			instrument,
			tradingDate,
			candleTime,
			new BigDecimal("71000"),
			new BigDecimal("71500"),
			new BigDecimal("70900"),
			new BigDecimal(close),
			123456L,
			"KRX_REPLAY",
			LocalDateTime.now());
	}

	@Test
	void saveAndFindStockCandlePersistsAllFieldsCorrectly() {
		StockCandle saved = stockCandleRepository.save(
			newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));

		Optional<StockCandle> found = stockCandleRepository.findById(saved.getId());

		assertThat(found).isPresent();
		StockCandle candle = found.get();
		assertThat(candle.getInstrument().getId()).isEqualTo(instrumentA.getId());
		assertThat(candle.getTradingDate()).isEqualTo(TRADING_DATE);
		assertThat(candle.getCandleTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(candle.getOpen()).isEqualByComparingTo("71000");
		assertThat(candle.getHigh()).isEqualByComparingTo("71500");
		assertThat(candle.getLow()).isEqualByComparingTo("70900");
		assertThat(candle.getClose()).isEqualByComparingTo("71200");
		assertThat(candle.getVolume()).isEqualTo(123456L);
		assertThat(candle.getDataSource()).isEqualTo("KRX_REPLAY");
		assertThat(candle.getCollectedAt()).isNotNull();
	}

	@Test
	void databaseRejectsDuplicateInstrumentTradingDateAndCandleTime() {
		stockCandleRepository.saveAndFlush(
			newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));

		StockCandle duplicate = newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "99999");

		assertThatThrownBy(() -> stockCandleRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameInstrumentAndTimeOnDifferentTradingDateDoesNotViolateUniqueConstraint() {
		stockCandleRepository.saveAndFlush(
			newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));

		StockCandle otherDaySameTime = newCandle(instrumentA, OTHER_TRADING_DATE, LocalTime.of(9, 0), "72000");

		assertThat(stockCandleRepository.saveAndFlush(otherDaySameTime).getId()).isNotNull();
	}

	@Test
	void findByInstrumentIdAndTradingDateOrderByCandleTimeAscReturnsOnlyThatDayOrderedByTimeAscending() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 2), "71400"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 1), "71300"));
		stockCandleRepository.save(newCandle(instrumentA, OTHER_TRADING_DATE, LocalTime.of(9, 0), "80000"));
		stockCandleRepository.save(newCandle(instrumentB, TRADING_DATE, LocalTime.of(9, 0), "90000"));

		List<StockCandle> candles = stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
			instrumentA.getId(), TRADING_DATE);

		assertThat(candles).hasSize(3);
		assertThat(candles).extracting(StockCandle::getCandleTime)
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 1), LocalTime.of(9, 2));
		assertThat(candles).allMatch(candle -> candle.getInstrument().getId().equals(instrumentA.getId()));
	}

	@Test
	void findByInstrumentIdAndTradingDateAndCandleTimeReturnsExactMatchOnly() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 1), "71300"));

		Optional<StockCandle> found = stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTime(
			instrumentA.getId(), TRADING_DATE, LocalTime.of(9, 1));

		assertThat(found).isPresent();
		assertThat(found.get().getClose()).isEqualByComparingTo("71300");
	}

	@Test
	void findByInstrumentIdAndTradingDateAndCandleTimeReturnsEmptyWhenNoCandleAtThatExactTime() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));

		Optional<StockCandle> found = stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTime(
			instrumentA.getId(), TRADING_DATE, LocalTime.of(9, 5));

		assertThat(found).isEmpty();
	}

	@Test
	void findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAscReturnsTheFirstMinuteCandle() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 1), "71300"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 2), "71400"));

		Optional<StockCandle> first = stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrumentA.getId(), TRADING_DATE);

		assertThat(first).isPresent();
		assertThat(first.get().getCandleTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(first.get().getClose()).isEqualByComparingTo("71200");
	}

	@Test
	void findFirstByCandleTimeLessThanEqualOrderByCandleTimeDescReturnsLatestClosedCandleAsOfGivenTime() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 1), "71300"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 2), "71400"));

		Optional<StockCandle> latestAsOf = stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				instrumentA.getId(), TRADING_DATE, LocalTime.of(9, 1, 30));

		assertThat(latestAsOf).isPresent();
		assertThat(latestAsOf.get().getCandleTime()).isEqualTo(LocalTime.of(9, 1));
		assertThat(latestAsOf.get().getClose()).isEqualByComparingTo("71300");
	}

	@Test
	void findFirstByCandleTimeLessThanEqualOrderByCandleTimeDescReturnsEmptyBeforeFirstCandleTime() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));

		Optional<StockCandle> beforeOpen = stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				instrumentA.getId(), TRADING_DATE, LocalTime.of(8, 59, 59));

		assertThat(beforeOpen).isEmpty();
	}

	@Test
	void findFirstByCandleTimeLessThanEqualOrderByCandleTimeDescAtExactCandleTimeReturnsThatCandle() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 1), "71300"));

		Optional<StockCandle> exact = stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				instrumentA.getId(), TRADING_DATE, LocalTime.of(9, 1));

		assertThat(exact).isPresent();
		assertThat(exact.get().getCandleTime()).isEqualTo(LocalTime.of(9, 1));
	}

	@Test
	void findFirstByCandleTimeDescReturnsTheLastCandleOfTheDayEvenWhenItIsNotAtHalfPastThree() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(15, 27), "73000"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(12, 0), "72000"));

		Optional<StockCandle> last = stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(instrumentA.getId(), TRADING_DATE);

		assertThat(last).isPresent();
		assertThat(last.get().getCandleTime()).isEqualTo(LocalTime.of(15, 27));
		assertThat(last.get().getClose()).isEqualByComparingTo("73000");
	}

	@Test
	void findFirstByCandleTimeDescReturnsEmptyWhenThatInstrumentHasNoCandleOnThatDate() {
		stockCandleRepository.save(newCandle(instrumentA, OTHER_TRADING_DATE, LocalTime.of(15, 27), "73000"));
		stockCandleRepository.save(newCandle(instrumentB, TRADING_DATE, LocalTime.of(15, 27), "99000"));

		Optional<StockCandle> last = stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(instrumentA.getId(), TRADING_DATE);

		assertThat(last).isEmpty();
	}

	@Test
	void findFirstByCandleTimeDescDoesNotLookAtOtherInstrumentsLaterCandle() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentB, TRADING_DATE, LocalTime.of(15, 29), "99000"));

		Optional<StockCandle> last = stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(instrumentA.getId(), TRADING_DATE);

		assertThat(last).isPresent();
		assertThat(last.get().getCandleTime()).isEqualTo(LocalTime.of(9, 0));
	}

	@Test
	void findByCandleTimeBetweenReturnsOnlyCandlesWithinRangeOrderedByTimeAscending() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 2), "71400"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 1), "71300"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(8, 59), "71100"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 3), "71500"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				instrumentA.getId(), TRADING_DATE, LocalTime.of(9, 0), LocalTime.of(9, 2));

		assertThat(candles).hasSize(3);
		assertThat(candles).extracting(StockCandle::getCandleTime)
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 1), LocalTime.of(9, 2));
	}

	@Test
	void findByCandleTimeBetweenIsInclusiveOfBothRangeEndpoints() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 1), "71300"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				instrumentA.getId(), TRADING_DATE, LocalTime.of(9, 0), LocalTime.of(9, 1));

		assertThat(candles).hasSize(2);
	}

	@Test
	void findByCandleTimeBetweenExcludesOtherTradingDateAndOtherInstrument() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));
		stockCandleRepository.save(newCandle(instrumentA, OTHER_TRADING_DATE, LocalTime.of(9, 0), "80000"));
		stockCandleRepository.save(newCandle(instrumentB, TRADING_DATE, LocalTime.of(9, 0), "90000"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				instrumentA.getId(), TRADING_DATE, LocalTime.MIN, LocalTime.of(23, 59, 59));

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).getClose()).isEqualByComparingTo("71200");
	}

	@Test
	void findByCandleTimeBetweenReturnsEmptyWhenNoCandleFallsWithinRange() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 5), "71600"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				instrumentA.getId(), TRADING_DATE, LocalTime.of(9, 0), LocalTime.of(9, 2));

		assertThat(candles).isEmpty();
	}

	@Test
	void existsByTradingDateReturnsTrueWhenAnyInstrumentHasACandleOnThatDate() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));

		assertThat(stockCandleRepository.existsByTradingDate(TRADING_DATE)).isTrue();
	}

	@Test
	void existsByTradingDateIsTrueRegardlessOfWhichInstrumentHoldsTheCandle() {
		stockCandleRepository.save(newCandle(instrumentB, TRADING_DATE, LocalTime.of(9, 0), "90000"));

		assertThat(stockCandleRepository.existsByTradingDate(TRADING_DATE)).isTrue();
	}

	@Test
	void existsByTradingDateReturnsFalseWhenNoCandleExistsOnThatDate() {
		stockCandleRepository.save(newCandle(instrumentA, OTHER_TRADING_DATE, LocalTime.of(9, 0), "80000"));

		assertThat(stockCandleRepository.existsByTradingDate(TRADING_DATE)).isFalse();
	}

	@Test
	void findByTradingDateBetweenReturnsCandlesAcrossMultipleTradingDatesOrderedByDateThenTimeAscending() {
		LocalDate day1 = LocalDate.of(2026, 7, 20);
		LocalDate day2 = LocalDate.of(2026, 7, 21);
		LocalDate day3 = LocalDate.of(2026, 7, 22);
		stockCandleRepository.save(newCandle(instrumentA, day2, LocalTime.of(9, 1), "71300"));
		stockCandleRepository.save(newCandle(instrumentA, day1, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentA, day3, LocalTime.of(9, 0), "72000"));
		stockCandleRepository.save(newCandle(instrumentA, day2, LocalTime.of(9, 0), "71200"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
				instrumentA.getId(), day1, day3);

		assertThat(candles).hasSize(4);
		assertThat(candles).extracting(c -> c.getTradingDate() + "T" + c.getCandleTime())
			.containsExactly(day1 + "T09:00", day2 + "T09:00", day2 + "T09:01", day3 + "T09:00");
	}

	@Test
	void findByTradingDateBetweenIsInclusiveOfBothDateEndpoints() {
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 22);
		stockCandleRepository.save(newCandle(instrumentA, from, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentA, to, LocalTime.of(9, 0), "72000"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(instrumentA.getId(), from, to);

		assertThat(candles).hasSize(2);
		assertThat(candles).extracting(StockCandle::getTradingDate).containsExactly(from, to);
	}

	@Test
	void findByTradingDateBetweenExcludesDatesOutsideRangeIncludingAFutureTradingDateBeyondTo() {
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 22);
		LocalDate beforeRange = LocalDate.of(2026, 7, 19);
		LocalDate futureBeyondTo = LocalDate.of(2026, 7, 23);
		stockCandleRepository.save(newCandle(instrumentA, from, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentA, beforeRange, LocalTime.of(9, 0), "70000"));
		stockCandleRepository.save(newCandle(instrumentA, futureBeyondTo, LocalTime.of(9, 0), "73000"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(instrumentA.getId(), from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).getTradingDate()).isEqualTo(from);
		assertThat(candles).extracting(StockCandle::getTradingDate).doesNotContain(beforeRange, futureBeyondTo);
	}

	@Test
	void findByTradingDateBetweenExcludesOtherInstrumentEvenWithinRange() {
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 22);
		stockCandleRepository.save(newCandle(instrumentA, from, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentB, from, LocalTime.of(9, 0), "99000"));

		List<StockCandle> candles = stockCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(instrumentA.getId(), from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles).allMatch(c -> c.getInstrument().getId().equals(instrumentA.getId()));
	}

	@Test
	void existsByInstrumentIdAndTradingDateReturnsTrueWhenThatInstrumentHasACandleOnThatDate() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71200"));

		assertThat(stockCandleRepository.existsByInstrumentIdAndTradingDate(instrumentA.getId(), TRADING_DATE))
			.isTrue();
	}

	@Test
	void existsByInstrumentIdAndTradingDateReturnsFalseWhenOnlyAnotherInstrumentHasACandleOnThatDate() {
		stockCandleRepository.save(newCandle(instrumentB, TRADING_DATE, LocalTime.of(9, 0), "90000"));

		assertThat(stockCandleRepository.existsByInstrumentIdAndTradingDate(instrumentA.getId(), TRADING_DATE))
			.isFalse();
	}

	@Test
	void existsByInstrumentIdAndTradingDateReturnsFalseWhenSameInstrumentHasCandleOnlyOnAnotherDate() {
		stockCandleRepository.save(newCandle(instrumentA, OTHER_TRADING_DATE, LocalTime.of(9, 0), "80000"));

		assertThat(stockCandleRepository.existsByInstrumentIdAndTradingDate(instrumentA.getId(), TRADING_DATE))
			.isFalse();
	}

	@Test
	void findDistinctTradingDateDedupesMultipleCandlesOnTheSameTradingDate() {
		LocalDate day1 = LocalDate.of(2026, 7, 20);
		LocalDate day2 = LocalDate.of(2026, 7, 21);
		stockCandleRepository.save(newCandle(instrumentA, day1, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentA, day1, LocalTime.of(9, 1), "71100"));
		stockCandleRepository.save(newCandle(instrumentA, day2, LocalTime.of(9, 0), "72000"));

		List<LocalDate> dates = stockCandleRepository
			.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
				instrumentA.getId(), day1, day2, PageRequest.of(0, 10));

		assertThat(dates).containsExactly(day2, day1);
	}

	@Test
	void findDistinctTradingDateIsInclusiveOfBothRangeEndpoints() {
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 22);
		LocalDate beforeRange = LocalDate.of(2026, 7, 19);
		LocalDate afterRange = LocalDate.of(2026, 7, 23);
		stockCandleRepository.save(newCandle(instrumentA, beforeRange, LocalTime.of(9, 0), "70000"));
		stockCandleRepository.save(newCandle(instrumentA, from, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentA, to, LocalTime.of(9, 0), "73000"));
		stockCandleRepository.save(newCandle(instrumentA, afterRange, LocalTime.of(9, 0), "74000"));

		List<LocalDate> dates = stockCandleRepository
			.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
				instrumentA.getId(), from, to, PageRequest.of(0, 10));

		assertThat(dates).containsExactly(to, from);
	}

	@Test
	void findDistinctTradingDatePageableLimitsResultCountToTheMostRecentDates() {
		LocalDate day1 = LocalDate.of(2026, 7, 20);
		LocalDate day2 = LocalDate.of(2026, 7, 21);
		LocalDate day3 = LocalDate.of(2026, 7, 22);
		stockCandleRepository.save(newCandle(instrumentA, day1, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentA, day2, LocalTime.of(9, 0), "72000"));
		stockCandleRepository.save(newCandle(instrumentA, day3, LocalTime.of(9, 0), "73000"));

		List<LocalDate> dates = stockCandleRepository
			.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
				instrumentA.getId(), day1, day3, PageRequest.of(0, 2));

		assertThat(dates).containsExactly(day3, day2);
	}

	@Test
	void findDistinctTradingDateExcludesOtherInstrumentEvenWithinRange() {
		LocalDate day1 = LocalDate.of(2026, 7, 20);
		stockCandleRepository.save(newCandle(instrumentA, day1, LocalTime.of(9, 0), "71000"));
		stockCandleRepository.save(newCandle(instrumentB, day1, LocalTime.of(9, 0), "99000"));

		List<LocalDate> dates = stockCandleRepository
			.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
				instrumentA.getId(), day1, day1, PageRequest.of(0, 10));

		assertThat(dates).containsExactly(day1);
	}

	@Test
	void findDistinctTradingDateReturnsEmptyWhenNoCandleFallsWithinRange() {
		stockCandleRepository.save(newCandle(instrumentA, TRADING_DATE, LocalTime.of(9, 0), "71000"));

		List<LocalDate> dates = stockCandleRepository
			.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
				instrumentA.getId(), OTHER_TRADING_DATE, OTHER_TRADING_DATE, PageRequest.of(0, 10));

		assertThat(dates).isEmpty();
	}
}
