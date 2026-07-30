// 실제 MySQL에서 종목 시드 데이터와 symbol UNIQUE 제약을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class InstrumentRepositoryTest {

	@Autowired
	private InstrumentRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void seedDataContainsSixteenStocksAndTwelveCryptosTotalingTwentyEight() {
		List<Instrument> all = repository.findAllByOrderByIdAsc();

		assertThat(all).hasSize(28);
		assertThat(all.stream().filter(instrument -> instrument.getMarket() == Market.STOCK).count())
			.isEqualTo(16);
		assertThat(all.stream().filter(instrument -> instrument.getMarket() == Market.CRYPTO).count())
			.isEqualTo(12);
	}

	@Test
	void seedSymbolsAreAllUniqueAcrossBothMarkets() {
		List<Instrument> all = repository.findAllByOrderByIdAsc();

		assertThat(all).extracting(Instrument::getSymbol).doesNotHaveDuplicates();
	}

	@Test
	void findByMarketOrderByIdAscReturnsOnlySixteenStockInstruments() {
		List<Instrument> stocks = repository.findByMarketOrderByIdAsc(Market.STOCK);

		assertThat(stocks).hasSize(16);
		assertThat(stocks).allMatch(instrument -> instrument.getMarket() == Market.STOCK);
	}

	@Test
	void findByMarketOrderByIdAscReturnsOnlyTwelveCryptoInstruments() {
		List<Instrument> cryptos = repository.findByMarketOrderByIdAsc(Market.CRYPTO);

		assertThat(cryptos).hasSize(12);
		assertThat(cryptos).allMatch(instrument -> instrument.getMarket() == Market.CRYPTO);
	}

	@Test
	void findByMarketAndTradableTrueOrderByIdAscExcludesNonTradableInstruments() {
		jdbcTemplate.update(
			"insert into instruments"
				+ "(market, symbol, name, tick_size, min_order_amount, tradable, created_at) "
				+ "values (?,?,?,?,?,?,?)",
			"CRYPTO", "DELISTED", "상장폐지코인", 1, 5000, false, LocalDateTime.now());

		List<Instrument> tradableCryptos = repository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);

		assertThat(tradableCryptos).hasSize(12);
		assertThat(tradableCryptos).extracting(Instrument::getSymbol).doesNotContain("DELISTED");
	}

	@Test
	void databaseRejectsDuplicateSymbolEvenAcrossDifferentMarkets() {
		// 005930(삼성전자, STOCK)은 시드에 이미 존재한다. 다른 시장(CRYPTO)에서 같은 symbol을 넣어도
		// UNIQUE 제약이 market과 무관하게 symbol 단독으로 걸려있는지 검증한다.
		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into instruments"
				+ "(market, symbol, name, tick_size, min_order_amount, tradable, created_at) "
				+ "values (?,?,?,?,?,?,?)",
			"CRYPTO", "005930", "duplicate-symbol", 1, 5000, true, LocalDateTime.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
