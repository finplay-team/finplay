package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TutorialSampleInstrumentPriceServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 10, 0, 0);

	@Test
	void returnsSamePriceForSameInstrumentAndSameClockInstant() {
		Clock clock = Clock.fixed(Instant.parse("2026-07-28T01:23:45Z"), ZoneOffset.UTC);
		TutorialSampleInstrumentPriceService service = new TutorialSampleInstrumentPriceService(clock);
		Instrument instrument = sampleInstrument(Market.STOCK, 1L);

		PriceQuoteDto first = service.getPriceQuote(instrument);
		PriceQuoteDto second = service.getPriceQuote(instrument);

		assertThat(first.price()).isEqualByComparingTo(second.price());
		assertThat(first.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(second.status()).isEqualTo(PriceStatus.AVAILABLE);
	}

	@Test
	void returnsDifferentPriceWhenClockInstantChangesEnoughToMoveThePhase() {
		Instrument instrument = sampleInstrument(Market.STOCK, 1L);
		Clock clockA = Clock.fixed(Instant.ofEpochSecond(0), ZoneOffset.UTC);
		Clock clockB = Clock.fixed(Instant.ofEpochSecond(45), ZoneOffset.UTC);

		PriceQuoteDto priceAtA = new TutorialSampleInstrumentPriceService(clockA).getPriceQuote(instrument);
		PriceQuoteDto priceAtB = new TutorialSampleInstrumentPriceService(clockB).getPriceQuote(instrument);

		assertThat(priceAtA.price()).isNotEqualByComparingTo(priceAtB.price());
	}

	@Test
	void stockSamplePriceStaysWithinThreePercentOfFiftyThousandBasePrice() {
		Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
		TutorialSampleInstrumentPriceService service = new TutorialSampleInstrumentPriceService(clock);
		Instrument instrument = sampleInstrument(Market.STOCK, 3L);

		BigDecimal price = service.getPriceQuote(instrument).price();

		assertThat(price).isGreaterThanOrEqualTo(new BigDecimal("48500.00000000"));
		assertThat(price).isLessThanOrEqualTo(new BigDecimal("51500.00000000"));
	}

	@Test
	void cryptoSamplePriceStaysWithinThreePercentOfTenThousandBasePrice() {
		Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
		TutorialSampleInstrumentPriceService service = new TutorialSampleInstrumentPriceService(clock);
		Instrument instrument = sampleInstrument(Market.CRYPTO, 4L);

		BigDecimal price = service.getPriceQuote(instrument).price();

		assertThat(price).isGreaterThanOrEqualTo(new BigDecimal("9700.00000000"));
		assertThat(price).isLessThanOrEqualTo(new BigDecimal("10300.00000000"));
	}

	@Test
	void stockAndCryptoSamplesUseDifferentBasePricesAtTheSameInstant() {
		Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
		TutorialSampleInstrumentPriceService service = new TutorialSampleInstrumentPriceService(clock);
		Instrument stock = sampleInstrument(Market.STOCK, 1L);
		Instrument crypto = sampleInstrument(Market.CRYPTO, 1L);

		BigDecimal stockPrice = service.getPriceQuote(stock).price();
		BigDecimal cryptoPrice = service.getPriceQuote(crypto).price();

		assertThat(stockPrice).isGreaterThan(new BigDecimal("48500.00000000"));
		assertThat(cryptoPrice).isLessThan(new BigDecimal("10300.00000000"));
		assertThat(stockPrice).isGreaterThan(cryptoPrice.multiply(BigDecimal.valueOf(2)));
	}

	@Test
	void getPriceQuoteAlwaysReturnsAvailableStatusWithNoSourceTradingDate() {
		Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
		TutorialSampleInstrumentPriceService service = new TutorialSampleInstrumentPriceService(clock);
		Instrument instrument = sampleInstrument(Market.CRYPTO, 2L);

		PriceQuoteDto result = service.getPriceQuote(instrument);

		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.price()).isNotNull();
		assertThat(result.sourceTradingDate()).isNull();
	}

	private Instrument sampleInstrument(Market market, long id) {
		Instrument instrument = Instrument.create(
			market, "SANDBOX_" + market + "_" + id, "연습용", BigDecimal.ONE, 10000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", id);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}
}
