// 실제 MySQL에서 stock_daily_candles 저장·조회와 UNIQUE(instrument_id, trading_date) 제약을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.domain.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class StockDailyCandleRepositoryTest {

	@Autowired
	private StockDailyCandleRepository stockDailyCandleRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	private Instrument instrumentA;
	private Instrument instrumentB;

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 22);
	private static final LocalDate OTHER_TRADING_DATE = LocalDate.of(2026, 7, 23);

	@BeforeEach
	void setUp() {
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
		instrumentA = instrumentRepository.save(Instrument.create(
			Market.STOCK, "DTEST001", "일봉테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		instrumentB = instrumentRepository.save(Instrument.create(
			Market.STOCK, "DTEST002", "일봉테스트종목B", new BigDecimal("500"), 180000, true, LocalDateTime.now()));
	}

	private StockDailyCandle newCandle(Instrument instrument, LocalDate tradingDate, String close) {
		return StockDailyCandle.create(
			instrument,
			tradingDate,
			new BigDecimal("71000"),
			new BigDecimal("71500"),
			new BigDecimal("70900"),
			new BigDecimal(close),
			123456L,
			"KIS_DAILY",
			LocalDateTime.now());
	}

	@Test
	void saveAndFindStockDailyCandlePersistsAllFieldsCorrectly() {
		StockDailyCandle saved = stockDailyCandleRepository.save(newCandle(instrumentA, TRADING_DATE, "71200"));

		Optional<StockDailyCandle> found = stockDailyCandleRepository.findById(saved.getId());

		assertThat(found).isPresent();
		StockDailyCandle candle = found.get();
		assertThat(candle.getInstrument().getId()).isEqualTo(instrumentA.getId());
		assertThat(candle.getTradingDate()).isEqualTo(TRADING_DATE);
		assertThat(candle.getOpen()).isEqualByComparingTo("71000");
		assertThat(candle.getHigh()).isEqualByComparingTo("71500");
		assertThat(candle.getLow()).isEqualByComparingTo("70900");
		assertThat(candle.getClose()).isEqualByComparingTo("71200");
		assertThat(candle.getVolume()).isEqualTo(123456L);
		assertThat(candle.getDataSource()).isEqualTo("KIS_DAILY");
		assertThat(candle.getCollectedAt()).isNotNull();
	}

	// --- UNIQUE(instrument_id, trading_date) ---

	@Test
	void databaseRejectsDuplicateInstrumentAndTradingDate() {
		stockDailyCandleRepository.saveAndFlush(newCandle(instrumentA, TRADING_DATE, "71200"));

		StockDailyCandle duplicate = newCandle(instrumentA, TRADING_DATE, "99999");

		assertThatThrownBy(() -> stockDailyCandleRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameInstrumentOnDifferentTradingDateDoesNotViolateUniqueConstraint() {
		stockDailyCandleRepository.saveAndFlush(newCandle(instrumentA, TRADING_DATE, "71200"));

		StockDailyCandle otherDay = newCandle(instrumentA, OTHER_TRADING_DATE, "72000");

		assertThat(stockDailyCandleRepository.saveAndFlush(otherDay).getId()).isNotNull();
	}

	@Test
	void sameTradingDateOnDifferentInstrumentDoesNotViolateUniqueConstraint() {
		stockDailyCandleRepository.saveAndFlush(newCandle(instrumentA, TRADING_DATE, "71200"));

		StockDailyCandle otherInstrument = newCandle(instrumentB, TRADING_DATE, "90000");

		assertThat(stockDailyCandleRepository.saveAndFlush(otherInstrument).getId()).isNotNull();
	}

	// --- 종목별 최신 거래일 조회(findFirstByInstrumentIdOrderByTradingDateDesc) ---

	@Test
	void findFirstByInstrumentIdOrderByTradingDateDescReturnsTheMostRecentTradingDate() {
		LocalDate day1 = LocalDate.of(2026, 7, 20);
		LocalDate day2 = LocalDate.of(2026, 7, 21);
		LocalDate day3 = LocalDate.of(2026, 7, 22);
		stockDailyCandleRepository.save(newCandle(instrumentA, day2, "71300"));
		stockDailyCandleRepository.save(newCandle(instrumentA, day1, "71000"));
		stockDailyCandleRepository.save(newCandle(instrumentA, day3, "72000"));

		Optional<StockDailyCandle> latest = stockDailyCandleRepository
			.findFirstByInstrumentIdOrderByTradingDateDesc(instrumentA.getId());

		assertThat(latest).isPresent();
		assertThat(latest.get().getTradingDate()).isEqualTo(day3);
		assertThat(latest.get().getClose()).isEqualByComparingTo("72000");
	}

	@Test
	void findFirstByInstrumentIdOrderByTradingDateDescIgnoresOtherInstrumentsLaterDate() {
		stockDailyCandleRepository.save(newCandle(instrumentA, TRADING_DATE, "71200"));
		// 다른 종목이 더 늦은 거래일을 갖고 있어도 instrumentA의 최신 거래일이 나와야 한다.
		stockDailyCandleRepository.save(newCandle(instrumentB, OTHER_TRADING_DATE, "99000"));

		Optional<StockDailyCandle> latest = stockDailyCandleRepository
			.findFirstByInstrumentIdOrderByTradingDateDesc(instrumentA.getId());

		assertThat(latest).isPresent();
		assertThat(latest.get().getTradingDate()).isEqualTo(TRADING_DATE);
	}

	@Test
	void findFirstByInstrumentIdOrderByTradingDateDescReturnsEmptyWhenInstrumentHasNoCandle() {
		Optional<StockDailyCandle> latest = stockDailyCandleRepository
			.findFirstByInstrumentIdOrderByTradingDateDesc(instrumentA.getId());

		assertThat(latest).isEmpty();
	}

	// --- 기간 조회 정렬(findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc) ---

	@Test
	void findByTradingDateBetweenReturnsCandlesOrderedByTradingDateAscending() {
		LocalDate day1 = LocalDate.of(2026, 7, 20);
		LocalDate day2 = LocalDate.of(2026, 7, 21);
		LocalDate day3 = LocalDate.of(2026, 7, 22);
		stockDailyCandleRepository.save(newCandle(instrumentA, day3, "72000"));
		stockDailyCandleRepository.save(newCandle(instrumentA, day1, "71000"));
		stockDailyCandleRepository.save(newCandle(instrumentA, day2, "71300"));

		List<StockDailyCandle> candles = stockDailyCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(instrumentA.getId(), day1, day3);

		assertThat(candles).hasSize(3);
		assertThat(candles).extracting(StockDailyCandle::getTradingDate).containsExactly(day1, day2, day3);
	}

	@Test
	void findByTradingDateBetweenIsInclusiveOfBothDateEndpoints() {
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 22);
		stockDailyCandleRepository.save(newCandle(instrumentA, from, "71000"));
		stockDailyCandleRepository.save(newCandle(instrumentA, to, "72000"));

		List<StockDailyCandle> candles = stockDailyCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(instrumentA.getId(), from, to);

		assertThat(candles).hasSize(2);
		assertThat(candles).extracting(StockDailyCandle::getTradingDate).containsExactly(from, to);
	}

	@Test
	void findByTradingDateBetweenExcludesDatesOutsideRange() {
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 22);
		LocalDate beforeRange = LocalDate.of(2026, 7, 19);
		LocalDate afterRange = LocalDate.of(2026, 7, 23);
		stockDailyCandleRepository.save(newCandle(instrumentA, from, "71000"));
		stockDailyCandleRepository.save(newCandle(instrumentA, beforeRange, "70000"));
		stockDailyCandleRepository.save(newCandle(instrumentA, afterRange, "73000"));

		List<StockDailyCandle> candles = stockDailyCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(instrumentA.getId(), from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).getTradingDate()).isEqualTo(from);
	}

	@Test
	void findByTradingDateBetweenExcludesOtherInstrumentEvenWithinRange() {
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 22);
		stockDailyCandleRepository.save(newCandle(instrumentA, from, "71000"));
		stockDailyCandleRepository.save(newCandle(instrumentB, from, "99000"));

		List<StockDailyCandle> candles = stockDailyCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(instrumentA.getId(), from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles).allMatch(c -> c.getInstrument().getId().equals(instrumentA.getId()));
	}

	@Test
	void findByTradingDateBetweenReturnsEmptyWhenNoCandleFallsWithinRange() {
		stockDailyCandleRepository.save(newCandle(instrumentA, TRADING_DATE, "71000"));

		List<StockDailyCandle> candles = stockDailyCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(
				instrumentA.getId(), OTHER_TRADING_DATE, OTHER_TRADING_DATE);

		assertThat(candles).isEmpty();
	}
}
