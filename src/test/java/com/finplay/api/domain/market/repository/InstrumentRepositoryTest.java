package com.finplay.api.domain.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
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
		List<Instrument> nonSamples = all.stream().filter(instrument -> !instrument.isTutorialSample()).toList();

		assertThat(all).hasSize(34);
		assertThat(nonSamples).hasSize(28);
		assertThat(nonSamples.stream().filter(instrument -> instrument.getMarket() == Market.STOCK).count())
			.isEqualTo(16);
		assertThat(nonSamples.stream().filter(instrument -> instrument.getMarket() == Market.CRYPTO).count())
			.isEqualTo(12);
	}

	@Test
	void seedSymbolsAreAllUniqueAcrossBothMarkets() {
		List<Instrument> all = repository.findAllByOrderByIdAsc();

		assertThat(all).extracting(Instrument::getSymbol).doesNotHaveDuplicates();
	}

	@Test
	void findByMarketOrderByIdAscReturnsNineteenStockInstrumentsIncludingThreeTutorialSamples() {
		List<Instrument> stocks = repository.findByMarketOrderByIdAsc(Market.STOCK);

		assertThat(stocks).hasSize(19);
		assertThat(stocks).allMatch(instrument -> instrument.getMarket() == Market.STOCK);
		assertThat(stocks.stream().filter(Instrument::isTutorialSample).count()).isEqualTo(3);
	}

	@Test
	void findByMarketOrderByIdAscReturnsFifteenCryptoInstrumentsIncludingThreeTutorialSamples() {
		List<Instrument> cryptos = repository.findByMarketOrderByIdAsc(Market.CRYPTO);

		assertThat(cryptos).hasSize(15);
		assertThat(cryptos).allMatch(instrument -> instrument.getMarket() == Market.CRYPTO);
		assertThat(cryptos.stream().filter(Instrument::isTutorialSample).count()).isEqualTo(3);
	}

	@Test
	void findByMarketAndTradableTrueOrderByIdAscExcludesNonTradableInstruments() {
		jdbcTemplate.update(
			"insert into instruments"
				+ "(market, symbol, name, tick_size, min_order_amount, tradable, created_at) "
				+ "values (?,?,?,?,?,?,?)",
			"CRYPTO", "DELISTED", "상장폐지코인", 1, 5000, false, LocalDateTime.now());

		List<Instrument> tradableCryptos = repository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);

		assertThat(tradableCryptos).hasSize(13);
		assertThat(tradableCryptos).extracting(Instrument::getSymbol).doesNotContain("DELISTED");
	}

	@Test
	void findByMarketAndTutorialSampleFalseOrderByIdAscExcludesTutorialSampleInstruments() {
		List<Instrument> nonSampleStocks = repository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);

		assertThat(nonSampleStocks).hasSize(16);
		assertThat(nonSampleStocks).allMatch(instrument -> instrument.getMarket() == Market.STOCK);
		assertThat(nonSampleStocks).noneMatch(Instrument::isTutorialSample);
		assertThat(nonSampleStocks).extracting(Instrument::getSymbol)
			.doesNotContain("SANDBOX_STK_1", "SANDBOX_STK_2", "SANDBOX_STK_3");
	}

	@Test
	void findByMarketAndSymbolReturnsMatchingInstrument() {
		var result = repository.findByMarketAndSymbol(Market.CRYPTO, "BTC");

		assertThat(result).isPresent();
		assertThat(result.get().getSymbol()).isEqualTo("BTC");
		assertThat(result.get().getMarket()).isEqualTo(Market.CRYPTO);
	}

	@Test
	void findByMarketAndSymbolReturnsEmptyWhenSymbolExistsInOtherMarketOnly() {
		var result = repository.findByMarketAndSymbol(Market.STOCK, "BTC");

		assertThat(result).isEmpty();
	}

	@Test
	void findByMarketAndSymbolReturnsEmptyWhenSymbolDoesNotExist() {
		var result = repository.findByMarketAndSymbol(Market.CRYPTO, "NO_SUCH_SYMBOL");

		assertThat(result).isEmpty();
	}

	@Test
	void v32MigrationSeedsSixTutorialSampleInstrumentsWithExactlyOneTradablePerMarket() {
		List<Instrument> samples = repository.findAllByOrderByIdAsc().stream()
			.filter(Instrument::isTutorialSample)
			.toList();

		assertThat(samples).hasSize(6);
		assertThat(samples.stream().filter(instrument -> instrument.getMarket() == Market.STOCK).count())
			.isEqualTo(3);
		assertThat(samples.stream().filter(instrument -> instrument.getMarket() == Market.CRYPTO).count())
			.isEqualTo(3);
		assertThat(samples.stream()
			.filter(instrument -> instrument.getMarket() == Market.STOCK)
			.filter(Instrument::isTradable)
			.count())
			.isEqualTo(1);
		assertThat(samples.stream()
			.filter(instrument -> instrument.getMarket() == Market.CRYPTO)
			.filter(Instrument::isTradable)
			.count())
			.isEqualTo(1);
		assertThat(samples).extracting(Instrument::getSymbol)
			.containsExactlyInAnyOrder(
				"SANDBOX_STK_1", "SANDBOX_STK_2", "SANDBOX_STK_3",
				"SANDBOX_COIN_1", "SANDBOX_COIN_2", "SANDBOX_COIN_3");
	}

	@Test
	void tradableNonSandboxCryptoQueryExcludesSandboxInstruments() {
		List<Instrument> targets = repository
			.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO);

		assertThat(targets).isNotEmpty();
		assertThat(targets).allSatisfy(instrument -> {
			assertThat(instrument.isTutorialSample()).isFalse();
			assertThat(instrument.isTradable()).isTrue();
			assertThat(instrument.getMarket()).isEqualTo(Market.CRYPTO);
		});
		assertThat(targets).extracting(Instrument::getSymbol)
			.doesNotContain("SANDBOX_COIN_1", "SANDBOX_COIN_2", "SANDBOX_COIN_3");

		assertThat(repository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO))
			.extracting(Instrument::getSymbol)
			.contains("SANDBOX_COIN_1");
	}

	@Test
	void databaseRejectsDuplicateSymbolEvenAcrossDifferentMarkets() {
		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into instruments"
				+ "(market, symbol, name, tick_size, min_order_amount, tradable, created_at) "
				+ "values (?,?,?,?,?,?,?)",
			"CRYPTO", "005930", "duplicate-symbol", 1, 5000, true, LocalDateTime.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
