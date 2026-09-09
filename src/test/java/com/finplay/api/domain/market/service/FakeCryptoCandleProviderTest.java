package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeCryptoCandleProviderTest {

	private static final BigDecimal ONE = BigDecimal.ONE;

	private static CryptoCandleDto candleAt(LocalDateTime sourceTime) {
		return new CryptoCandleDto(sourceTime, ONE, ONE, ONE, ONE, ONE);
	}

	@Test
	void getCandlesReturnsUnfilteredListWhenFromAndToAreNull() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();
		List<CryptoCandleDto> candles = List.of(
			candleAt(LocalDateTime.of(2026, 7, 30, 9, 0)),
			candleAt(LocalDateTime.of(2026, 7, 30, 9, 1)));
		provider.setCandles("BTC", candles);

		assertThat(provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null)).isEqualTo(candles);
	}

	@Test
	void getCandlesFiltersByFromAndToInclusiveBounds() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();
		provider.setCandles("BTC", List.of(
			candleAt(LocalDateTime.of(2026, 7, 30, 9, 0)),
			candleAt(LocalDateTime.of(2026, 7, 30, 9, 1)),
			candleAt(LocalDateTime.of(2026, 7, 30, 9, 2)),
			candleAt(LocalDateTime.of(2026, 7, 30, 9, 3))));

		List<CryptoCandleDto> result = provider.getCandles(
			"BTC", CandleInterval.ONE_MINUTE, LocalDateTime.of(2026, 7, 30, 9, 1), LocalDateTime.of(2026, 7, 30, 9, 2));

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactly(
				LocalDateTime.of(2026, 7, 30, 9, 1),
				LocalDateTime.of(2026, 7, 30, 9, 2));
	}

	@Test
	void getCandlesReturnsEmptyListForUnknownSymbol() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();

		assertThat(provider.getCandles("ETH", CandleInterval.ONE_MINUTE, null, null)).isEmpty();
	}

	@Test
	void simulateFailureMakesSubsequentCallsThrowMarketDataProviderError() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();
		provider.setCandles("BTC", List.of(candleAt(LocalDateTime.of(2026, 7, 30, 9, 0))));

		provider.simulateFailure();

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void resetClearsSimulatedFailureAndAllowsNormalRetrievalAgain() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();
		provider.setCandles("BTC", List.of(candleAt(LocalDateTime.of(2026, 7, 30, 9, 0))));
		provider.simulateFailure();

		provider.reset();

		assertThat(provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null)).hasSize(1);
	}


	@Test
	void legacySetCandlesWithoutIntervalSeedsOnlyOneMinuteBucket() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();
		provider.setCandles("BTC", List.of(candleAt(LocalDateTime.of(2026, 7, 30, 9, 0))));

		assertThat(provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null)).hasSize(1);
		assertThat(provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null)).isEmpty();
		assertThat(provider.getCandles("BTC", CandleInterval.ONE_WEEK, null, null)).isEmpty();
		assertThat(provider.getCandles("BTC", CandleInterval.ONE_MONTH, null, null)).isEmpty();
	}


	@Test
	void setCandlesWithIntervalKeepsEachIntervalsSeedIndependent() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();
		List<CryptoCandleDto> minuteCandles = List.of(candleAt(LocalDateTime.of(2026, 7, 30, 9, 0)));
		List<CryptoCandleDto> dayCandles = List.of(
			candleAt(LocalDateTime.of(2026, 7, 28, 0, 0)),
			candleAt(LocalDateTime.of(2026, 7, 29, 0, 0)));
		List<CryptoCandleDto> weekCandles = List.of(candleAt(LocalDateTime.of(2026, 7, 27, 0, 0)));
		List<CryptoCandleDto> monthCandles = List.of(candleAt(LocalDateTime.of(2026, 7, 1, 0, 0)));

		provider.setCandles("BTC", CandleInterval.ONE_MINUTE, minuteCandles);
		provider.setCandles("BTC", CandleInterval.ONE_DAY, dayCandles);
		provider.setCandles("BTC", CandleInterval.ONE_WEEK, weekCandles);
		provider.setCandles("BTC", CandleInterval.ONE_MONTH, monthCandles);

		assertThat(provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null)).isEqualTo(minuteCandles);
		assertThat(provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null)).isEqualTo(dayCandles);
		assertThat(provider.getCandles("BTC", CandleInterval.ONE_WEEK, null, null)).isEqualTo(weekCandles);
		assertThat(provider.getCandles("BTC", CandleInterval.ONE_MONTH, null, null)).isEqualTo(monthCandles);
	}

	@Test
	void setCandlesWithIntervalKeepsSymbolsIndependentWithinSameInterval() {
		FakeCryptoCandleProvider provider = new FakeCryptoCandleProvider();
		List<CryptoCandleDto> btcDayCandles = List.of(candleAt(LocalDateTime.of(2026, 7, 30, 0, 0)));
		List<CryptoCandleDto> ethDayCandles = List.of(candleAt(LocalDateTime.of(2026, 7, 29, 0, 0)));

		provider.setCandles("BTC", CandleInterval.ONE_DAY, btcDayCandles);
		provider.setCandles("ETH", CandleInterval.ONE_DAY, ethDayCandles);

		assertThat(provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null)).isEqualTo(btcDayCandles);
		assertThat(provider.getCandles("ETH", CandleInterval.ONE_DAY, null, null)).isEqualTo(ethDayCandles);
	}
}
