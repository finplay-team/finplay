// PriceMoveItem의 ofCrypto 팩토리가 §C-9대로 구간을 계산하는지 검증하는 순수 단위 테스트다.
package com.finplay.api.feedback.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PriceMoveItemTest {

	private static final BigDecimal CHANGE_RATE = new BigDecimal("0.031000");
	private static final BigDecimal DETECTION_SCORE = new BigDecimal("3.4000");

	private static Instrument cryptoInstrument() {
		return Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 50_000_000L, true, LocalDateTime.now());
	}

	private static PriceMoveEvent cryptoEvent(Long id, LocalDateTime occurredAt, String narrative) {
		PriceMoveEvent event = PriceMoveEvent.createCrypto(
			cryptoInstrument(), occurredAt, CHANGE_RATE, DETECTION_SCORE, narrative, NarrativeSource.TEMPLATE,
			occurredAt);
		ReflectionTestUtils.setField(event, "id", id);
		return event;
	}

	@Test
	@DisplayName("windowEnd는 occurredAt이고 windowStart는 occurredAt - rollingWindowMinutes다")
	void computesWindowStartAsOccurredAtMinusRollingWindowMinutes() {
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 5, 14, 30, 0);
		PriceMoveEvent event = cryptoEvent(1L, occurredAt, "5분간 3.1% 상승했습니다.");

		PriceMoveItem item = PriceMoveItem.ofCrypto(event, List.of(), 5);

		assertThat(item.windowEnd()).isEqualTo(occurredAt);
		assertThat(item.windowStart()).isEqualTo(LocalDateTime.of(2026, 8, 5, 14, 25, 0));
	}

	// rolling-window-minutes 값이 바뀌어도(§C-7 운영 중 변경) 조회 시점 값을 그대로 빼는지 — 5로 하드코딩돼
	// 있으면 이 케이스가 어긋난다.
	@Test
	@DisplayName("rollingWindowMinutes가 다른 값이어도 그 값만큼 정확히 뺀다")
	void subtractsWhateverRollingWindowMinutesIsPassedIn() {
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 5, 14, 30, 0);
		PriceMoveEvent event = cryptoEvent(2L, occurredAt, "10분간 급등했습니다.");

		PriceMoveItem item = PriceMoveItem.ofCrypto(event, List.of(), 10);

		assertThat(item.windowStart()).isEqualTo(LocalDateTime.of(2026, 8, 5, 14, 20, 0));
	}

	// §C-9의 자정 함정 — occurred_at이 00:03이면 windowStart가 전날 23:58로 날짜가 넘어간다. 이 계산이
	// LocalDateTime끼리의 뺄셈이라 TIME 전용 계산과 달리 날짜가 자연히 따라온다.
	@Test
	@DisplayName("occurredAt이 자정 직후여도 windowStart가 전날로 정확히 넘어간다")
	void carriesWindowStartAcrossMidnightCorrectly() {
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 5, 0, 3, 0);
		PriceMoveEvent event = cryptoEvent(3L, occurredAt, "자정 직후 급락했습니다.");

		PriceMoveItem item = PriceMoveItem.ofCrypto(event, List.of(), 5);

		assertThat(item.windowStart()).isEqualTo(LocalDateTime.of(2026, 8, 4, 23, 58, 0));
		assertThat(item.windowStart()).isBefore(item.windowEnd());
	}

	@Test
	@DisplayName("id·eventType·changeRate·narrative를 이벤트에서 그대로 옮긴다")
	void copiesRemainingFieldsFromTheEvent() {
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 5, 14, 30, 0);
		PriceMoveEvent event = cryptoEvent(9L, occurredAt, "5분간 3.1% 상승했습니다.");

		PriceMoveItem item = PriceMoveItem.ofCrypto(event, List.of(), 5);

		assertThat(item.id()).isEqualTo(9L);
		assertThat(item.eventType()).isEqualTo(PriceMoveEventType.INTRADAY);
		assertThat(item.changeRate()).isEqualByComparingTo(CHANGE_RATE);
		assertThat(item.narrative()).isEqualTo("5분간 3.1% 상승했습니다.");
		assertThat(item.sources()).isEmpty();
	}
}
