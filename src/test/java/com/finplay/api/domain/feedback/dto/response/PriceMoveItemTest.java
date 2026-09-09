package com.finplay.api.domain.feedback.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
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

	@Test
	@DisplayName("rollingWindowMinutes가 다른 값이어도 그 값만큼 정확히 뺀다")
	void subtractsWhateverRollingWindowMinutesIsPassedIn() {
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 5, 14, 30, 0);
		PriceMoveEvent event = cryptoEvent(2L, occurredAt, "10분간 급등했습니다.");

		PriceMoveItem item = PriceMoveItem.ofCrypto(event, List.of(), 10);

		assertThat(item.windowStart()).isEqualTo(LocalDateTime.of(2026, 8, 5, 14, 20, 0));
	}

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
