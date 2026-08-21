// MarketPriceEvent의 JSON 직렬화 계약(sourceTime/emittedAt/sourceTradingDate 필드 구분, 코인은 sourceTradingDate 생략)을 검증하는 @JsonTest다.
package com.finplay.api.domain.market.dto.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class MarketPriceEventTest {

	// 실제 SSE 전송이 쓰는 것과 같은, Boot의 JacksonAutoConfiguration이 구성한 빈을 주입받는다 (ADR-0003 — 직렬화는 슬라이스에서 실제로 확인).
	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void serializesStockPriceEventWithSourceTimeEmittedAtAndSourceTradingDateAllDistinct() {
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 9, 1, 0);
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 9, 1, 3);
		LocalDate sourceTradingDate = LocalDate.of(2026, 7, 24);
		MarketPriceEvent event = new MarketPriceEvent(
			Market.STOCK, "005930", BigDecimal.valueOf(70100), sourceTime, emittedAt, sourceTradingDate,
			StockMarketStatus.OPEN);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asString()).isEqualTo("STOCK");
		assertThat(json.get("symbol").asString()).isEqualTo("005930");
		assertThat(json.get("price").asInt()).isEqualTo(70100);
		// sourceTime(원천 가격 시각)과 emittedAt(서버 전송 시각)이 서로 다른 값으로 각자의 필드에 들어가야 한다.
		assertThat(json.get("sourceTime").asString()).isEqualTo("2026-07-28T09:01:00");
		assertThat(json.get("emittedAt").asString()).isEqualTo("2026-07-28T09:01:03");
		assertThat(json.get("sourceTradingDate").asString()).isEqualTo("2026-07-24");
		assertThat(json.get("marketStatus").asString()).isEqualTo("OPEN");
	}

	@Test
	void serializesCryptoPriceEventOmitsSourceTradingDateFieldEntirely() {
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 10, 15, 30);
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 15, 31);
		MarketPriceEvent event = new MarketPriceEvent(
			Market.CRYPTO, "BTC", BigDecimal.valueOf(95000000), sourceTime, emittedAt, null,
			StockMarketStatus.OPEN);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asString()).isEqualTo("CRYPTO");
		assertThat(json.get("sourceTime").asString()).isEqualTo("2026-07-28T10:15:30");
		assertThat(json.get("emittedAt").asString()).isEqualTo("2026-07-28T10:15:31");
		assertThat(json.has("sourceTradingDate")).isFalse();
	}
}
