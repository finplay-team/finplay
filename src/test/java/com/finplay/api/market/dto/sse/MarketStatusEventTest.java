// MarketStatusEvent의 JSON 직렬화 계약(종목별 상태 변화 vs 시장 전체 상태 변화에서 null 필드 생략)을 검증하는 단위 테스트다.
package com.finplay.api.market.dto.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.market.service.StockMarketStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MarketStatusEventTest {

	private final ObjectMapper objectMapper = new ObjectMapper()
		.registerModule(new JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	@Test
	void serializesInstrumentLevelStatusEventWithSymbolAndStatusPresent() throws Exception {
		// 코인 stale 등 종목 단위 상태 변화는 symbol·status·reason이 모두 채워진다.
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 30, 0);
		MarketStatusEvent event = MarketStatusEvent.of(
			Market.CRYPTO, "BTC", StockMarketStatus.OPEN, PriceStatus.UNAVAILABLE, "STALE", emittedAt);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asText()).isEqualTo("CRYPTO");
		assertThat(json.get("symbol").asText()).isEqualTo("BTC");
		assertThat(json.get("marketStatus").asText()).isEqualTo("OPEN");
		assertThat(json.get("status").asText()).isEqualTo("UNAVAILABLE");
		assertThat(json.get("reason").asText()).isEqualTo("STALE");
		assertThat(json.get("emittedAt").asText()).isEqualTo("2026-07-28T10:30:00");
	}

	@Test
	void serializesMarketWideStatusEventOmitsSymbolStatusReasonWhenNull() throws Exception {
		// 장 마감 같은 시장 전체 상태 변화는 symbol·status·reason이 없다 — null이면 필드 자체가 생략되어야 한다.
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 15, 30, 0);
		MarketStatusEvent event = MarketStatusEvent.of(
			Market.STOCK, null, StockMarketStatus.CLOSED, null, null, emittedAt);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asText()).isEqualTo("STOCK");
		assertThat(json.get("marketStatus").asText()).isEqualTo("CLOSED");
		assertThat(json.get("emittedAt").asText()).isEqualTo("2026-07-28T15:30:00");
		assertThat(json.has("symbol")).isFalse();
		assertThat(json.has("status")).isFalse();
		assertThat(json.has("reason")).isFalse();
	}
}
