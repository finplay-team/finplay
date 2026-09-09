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
