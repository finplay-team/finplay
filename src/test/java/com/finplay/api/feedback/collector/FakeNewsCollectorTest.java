// 로컬·테스트용 FakeNewsCollector가 외부 호출 없이 항상 빈 목록을 돌려주는지 검증한다.
package com.finplay.api.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// tasks.md 3번의 검증 ④ — Fake는 빈 목록을 준다. spec §실패 처리의 "키 없음 → Fake가 빈 목록. 기동·테스트 정상"이
// 성립하는 근거이고, 동시에 "가짜 기사를 지어내지 않는다"를 고정한다. 더미 기사를 돌려주면 로컬 화면에 실제로
// 없었던 사건이 근거로 붙는다.
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
