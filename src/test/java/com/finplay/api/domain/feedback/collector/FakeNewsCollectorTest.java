package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeNewsCollectorTest {

	private final FakeNewsCollector collector = new FakeNewsCollector();

	@Test
	@DisplayName("주식·코인 어느 종목이든 빈 목록을 돌려준다")
	void returnsEmptyListForEveryInstrument() {
		assertThat(collector.collect(stock("삼성전자"), List.of("삼성전자", "삼성SDI"))).isEmpty();
		assertThat(collector.collect(crypto("비트코인"), List.of("비트코인", "비트코인캐시"))).isEmpty();
	}

	@Test
	@DisplayName("같은 시장 종목명 목록이 비어 있어도 예외 없이 빈 목록을 돌려준다")
	void returnsEmptyListWithoutThrowingWhenSameMarketNamesIsEmpty() {
		assertThat(collector.collect(crypto("이더리움"), List.of())).isEmpty();
	}

	private static Instrument stock(String name) {
		return Instrument.create(
			Market.STOCK, "000000", name, new BigDecimal("100"), 70000, true, LocalDateTime.now());
	}

	private static Instrument crypto(String name) {
		return Instrument.create(
			Market.CRYPTO, "SYM", name, new BigDecimal("1000"), 5000, true, LocalDateTime.now());
	}
}
