package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeDisclosureCollectorTest {

	private final FakeDisclosureCollector collector = new FakeDisclosureCollector();

	@Test
	@DisplayName("어느 주식 종목이든, 어느 수집일이든 빈 목록을 돌려준다")
	void returnsEmptyListForEveryInstrumentAndDate() {
		assertThat(collector.collect(stock("005930"), LocalDate.of(2026, 8, 4))).isEmpty();
		assertThat(collector.collect(stock("000660"), LocalDate.of(2026, 1, 2))).isEmpty();
	}

	@Test
	@DisplayName("주말·휴일 날짜로 불러도 예외 없이 빈 목록이다")
	void returnsEmptyListWithoutThrowingOnNonBusinessDay() {
		assertThat(collector.collect(stock("005930"), LocalDate.of(2026, 8, 2))).isEmpty();
	}

	private static Instrument stock(String symbol) {
		return Instrument.create(
			Market.STOCK, symbol, "삼성전자", new BigDecimal("100"), 70000, true, LocalDateTime.now());
	}
}
