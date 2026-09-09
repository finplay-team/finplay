package com.finplay.api.domain.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketCryptoPropertiesTest {

	@Test
	@DisplayName("sigma-lookback-hours가 1 미만이면 예외를 던진다")
	void rejectsSigmaLookbackHoursBelowOne() {
		assertThatThrownBy(() -> new MarketCryptoProperties("0 * * * * *", 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("market.crypto.sigma-lookback-hours");
	}

	@Test
	@DisplayName("sigma-lookback-hours가 1 이상이면 정상 생성된다")
	void acceptsSigmaLookbackHoursOfOneOrMore() {
		MarketCryptoProperties properties = new MarketCryptoProperties("0 * * * * *", 1);

		assertThat(properties.sigmaLookbackHours()).isEqualTo(1);
	}
}
