// market.crypto.sigma-lookback-hours 검증 규칙을 확인한다 — feedback.crypto.sigma-lookback-hours와 대칭이다.
package com.finplay.api.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// PR #236 리뷰 참고 사항 — FeedbackCryptoProperties는 sigmaLookbackHours < 1을 막는데
// MarketCryptoProperties는 대칭 검증이 없었다. 여기서 그 대칭을 맞춘다.
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
