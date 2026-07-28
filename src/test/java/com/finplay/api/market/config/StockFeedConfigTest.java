// STOCK_FEED_PROVIDER·SERVICE_EXPOSURE·KIS_PUBLIC_DISPLAY_APPROVED 허용/금지 조합(spec.md MKT-007)을 검증하는 단위 테스트
package com.finplay.api.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StockFeedConfigTest {

	// 허용 조합 넷: PRIVATE+KRX_REPLAY, PRIVATE+KIS_REALTIME, PUBLIC+KRX_REPLAY, PUBLIC+KIS_REALTIME+승인=true
	@ParameterizedTest
	@CsvSource({
		"PRIVATE, KRX_REPLAY, false",
		"PRIVATE, KIS_REALTIME, false",
		"PUBLIC, KRX_REPLAY, false",
		"PUBLIC, KIS_REALTIME, true"
	})
	void allowedCombinationDoesNotThrow(
		ServiceExposure exposure, StockFeedProvider provider, boolean kisPublicDisplayApproved) {
		assertThat(new StockFeedConfig(provider, exposure, kisPublicDisplayApproved)).isNotNull();
	}

	// 유일한 금지 조합: PUBLIC+KIS_REALTIME+미승인 — 서면 허가 없이 공개 실시간 표출로 전환하면 기동 자체가 실패해야 한다 (C-007, fail-fast).
	@Test
	void forbiddenCombinationThrowsIllegalStateException() {
		assertThatThrownBy(
			() -> new StockFeedConfig(StockFeedProvider.KIS_REALTIME, ServiceExposure.PUBLIC, false))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KIS_PUBLIC_DISPLAY_APPROVED");
	}
}
