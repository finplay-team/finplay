// 실제 MySQL에서 stock_candles 저장·조회와 UNIQUE(instrument_id, trading_date, candle_time) 제약을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
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
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
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
		// 다른 거래일 — 결과에서 제외되어야 함
		stockCandleRepository.save(newCandle(instrumentA, OTHER_TRADING_DATE, LocalTime.of(9, 0), "80000"));
		// 다른 종목, 같은 거래일 — 결과에서 제외되어야 함
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

		// 09:01:30 시점에는 아직 09:02 분봉이 마감되지 않았으므로 09:01 분봉이 "마감된 최신 분봉"이어야 한다.
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
}
