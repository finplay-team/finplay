package com.finplay.api.domain.market.dto.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class MarketStatusEventTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void serializesInstrumentLevelStatusEventWithSymbolAndStatusPresent() {
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 30, 0);
		MarketStatusEvent event = new MarketStatusEvent(
			Market.CRYPTO, "BTC", StockMarketStatus.OPEN, PriceStatus.UNAVAILABLE, "DISCONNECTED", emittedAt);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asString()).isEqualTo("CRYPTO");
		assertThat(json.get("symbol").asString()).isEqualTo("BTC");
		assertThat(json.get("marketStatus").asString()).isEqualTo("OPEN");
		assertThat(json.get("status").asString()).isEqualTo("UNAVAILABLE");
		assertThat(json.get("reason").asString()).isEqualTo("DISCONNECTED");
		assertThat(json.get("emittedAt").asString()).isEqualTo("2026-07-28T10:30:00");
	}

	@Test
	void serializesMarketWideStatusEventOmitsSymbolStatusReasonWhenNull() {
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 15, 30, 0);
		MarketStatusEvent event = new MarketStatusEvent(
			Market.STOCK, null, StockMarketStatus.CLOSED, null, null, emittedAt);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asString()).isEqualTo("STOCK");
		assertThat(json.get("marketStatus").asString()).isEqualTo("CLOSED");
		assertThat(json.get("emittedAt").asString()).isEqualTo("2026-07-28T15:30:00");
		assertThat(json.has("symbol")).isFalse();
		assertThat(json.has("status")).isFalse();
		assertThat(json.has("reason")).isFalse();
	}
}
