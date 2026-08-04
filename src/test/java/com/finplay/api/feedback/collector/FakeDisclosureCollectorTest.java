// 로컬·테스트용 FakeDisclosureCollector가 외부 호출 없이 항상 빈 목록을 돌려주는지 검증한다.
package com.finplay.api.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// spec §실패 처리의 "DART 키 없음 → Fake가 빈 목록. 기동·테스트 정상"이 성립하는 근거다. 동시에 "가짜 공시를
// 지어내지 않는다"를 고정한다 — 더미 공시를 돌려주면 실제로 접수되지 않은 공시가 카드 근거로 붙는다.
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
