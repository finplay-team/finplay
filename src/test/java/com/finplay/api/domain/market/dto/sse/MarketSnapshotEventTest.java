package com.finplay.api.domain.market.dto.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class MarketSnapshotEventTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void serializesStockSnapshotWithSourceTradingDateAndPricesIncludingUnavailableEntry() {
		LocalDate sourceTradingDate = LocalDate.of(2026, 7, 24);
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 0, 5);
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 9, 1, 0);
		MarketSnapshotEvent event = new MarketSnapshotEvent(
			Market.STOCK, sourceTradingDate, StockMarketStatus.OPEN, emittedAt,
			List.of(
				InstrumentPriceSnapshot.of("005930", BigDecimal.valueOf(70100), sourceTime, PriceStatus.AVAILABLE),
				InstrumentPriceSnapshot.of("000660", null, null, PriceStatus.UNAVAILABLE)));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asString()).isEqualTo("STOCK");
		assertThat(json.get("sourceTradingDate").asString()).isEqualTo("2026-07-24");
		assertThat(json.get("marketStatus").asString()).isEqualTo("OPEN");
		assertThat(json.get("emittedAt").asString()).isEqualTo("2026-07-28T10:00:05");
		assertThat(json.get("prices")).hasSize(2);
		assertThat(json.get("prices").get(0).get("symbol").asString()).isEqualTo("005930");
		assertThat(json.get("prices").get(0).get("price").asInt()).isEqualTo(70100);
		assertThat(json.get("prices").get(0).get("sourceTime").asString()).isEqualTo("2026-07-28T09:01:00");
		assertThat(json.get("prices").get(0).get("status").asString()).isEqualTo("AVAILABLE");
		assertThat(json.get("prices").get(1).get("symbol").asString()).isEqualTo("000660");
		assertThat(json.get("prices").get(1).get("price").isNull()).isTrue();
		assertThat(json.get("prices").get(1).get("sourceTime").isNull()).isTrue();
		assertThat(json.get("prices").get(1).get("status").asString()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void serializesCryptoSnapshotOmitsSourceTradingDateFieldEntirely() {
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 0, 5);
		MarketSnapshotEvent event = new MarketSnapshotEvent(
			Market.CRYPTO, null, StockMarketStatus.OPEN, emittedAt,
			List.of(InstrumentPriceSnapshot.of("BTC", BigDecimal.valueOf(95000000),
				LocalDateTime.of(2026, 7, 28, 10, 0, 0), PriceStatus.AVAILABLE)));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asString()).isEqualTo("CRYPTO");
		assertThat(json.has("sourceTradingDate")).isFalse();
		assertThat(json.get("marketStatus").asString()).isEqualTo("OPEN");
		assertThat(json.get("emittedAt").asString()).isEqualTo("2026-07-28T10:00:05");
	}
}
