// 자동 테스트에서 실제 빗썸 REST 대신 쓰는 FakeCryptoCandleProvider의 필터링·장애 시뮬레이션을 검증한다 (MKT-008)
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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
}
