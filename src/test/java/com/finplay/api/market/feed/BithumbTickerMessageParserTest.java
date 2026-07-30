// 고정된 샘플 페이로드로 BithumbTickerMessageParser의 파싱 결과·무시 규칙·예외 무전파를 검증하는 @JsonTest다.
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class BithumbTickerMessageParserTest {

	// 실제 운영 코드가 쓰는 것과 같은, Boot의 JacksonAutoConfiguration이 구성한 빈을 주입받는다 (ADR-0003 — 직렬화·역직렬화는 슬라이스에서 실제로 확인).
	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void parsesTickerMessageIntoSymbolPriceAndReceivedAt() {
		String payload = """
			{
			  "type": "ticker",
			  "content": {
			    "symbol": "BTC_KRW",
			    "closePrice": "52000000",
			    "date": "20260730",
			    "time": "153000"
			  }
			}
			""";

		Optional<BithumbTickerMessageParser.BithumbTick> result = BithumbTickerMessageParser.parse(objectMapper,
			payload);

		assertThat(result).isPresent();
		assertThat(result.get().symbol()).isEqualTo("BTC");
		assertThat(result.get().price()).isEqualByComparingTo(new BigDecimal("52000000"));
		assertThat(result.get().receivedAt()).isEqualTo(LocalDateTime.of(2026, 7, 30, 15, 30, 0));
	}

	@Test
	void ignoresNonTickerTypeMessageWithoutThrowing() {
		String subscribeAck = """
			{
			  "status": "0000",
			  "resmsg": "Filter Registered Successfully"
			}
			""";

		Optional<BithumbTickerMessageParser.BithumbTick> result = BithumbTickerMessageParser.parse(objectMapper,
			subscribeAck);

		assertThat(result).isEmpty();
	}

	@Test
	void ignoresTickerMessageWithMissingFieldsWithoutThrowing() {
		String malformed = """
			{
			  "type": "ticker",
			  "content": {
			    "symbol": "BTC_KRW"
			  }
			}
			""";

		Optional<BithumbTickerMessageParser.BithumbTick> result = BithumbTickerMessageParser.parse(objectMapper,
			malformed);

		assertThat(result).isEmpty();
	}

	@Test
	void neverThrowsEvenForCompletelyInvalidJson() {
		String invalidJson = "not a json payload";

		assertThatCode(() -> BithumbTickerMessageParser.parse(objectMapper, invalidJson)).doesNotThrowAnyException();
	}
}
