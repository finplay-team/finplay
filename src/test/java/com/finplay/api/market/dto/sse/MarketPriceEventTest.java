// MarketPriceEvent의 JSON 직렬화 계약(sourceTime/emittedAt/sourceTradingDate 필드 구분, 코인은 sourceTradingDate 생략)을 검증하는 단위 테스트다.
package com.finplay.api.market.dto.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MarketPriceEventTest {

	private final ObjectMapper objectMapper = new ObjectMapper()
		.registerModule(new JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	@Test
	void serializesStockPriceEventWithSourceTimeEmittedAtAndSourceTradingDateAllDistinct() throws Exception {
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 9, 1, 0);
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 9, 1, 3);
		LocalDate sourceTradingDate = LocalDate.of(2026, 7, 24);
		MarketPriceEvent event = MarketPriceEvent.of(
			Market.STOCK, "005930", BigDecimal.valueOf(70100), sourceTime, emittedAt, sourceTradingDate,
			StockMarketStatus.OPEN);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asText()).isEqualTo("STOCK");
		assertThat(json.get("symbol").asText()).isEqualTo("005930");
		assertThat(json.get("price").asInt()).isEqualTo(70100);
		// sourceTime(원천 가격 시각)과 emittedAt(서버 전송 시각)이 서로 다른 값으로 각자의 필드에 들어가야 한다.
		assertThat(json.get("sourceTime").asText()).isEqualTo("2026-07-28T09:01:00");
		assertThat(json.get("emittedAt").asText()).isEqualTo("2026-07-28T09:01:03");
		assertThat(json.get("sourceTradingDate").asText()).isEqualTo("2026-07-24");
		assertThat(json.get("marketStatus").asText()).isEqualTo("OPEN");
	}

	@Test
	void serializesCryptoPriceEventOmitsSourceTradingDateFieldEntirely() throws Exception {
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 10, 15, 30);
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 15, 31);
		MarketPriceEvent event = MarketPriceEvent.of(
			Market.CRYPTO, "BTC", BigDecimal.valueOf(95000000), sourceTime, emittedAt, null,
			StockMarketStatus.OPEN);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asText()).isEqualTo("CRYPTO");
		assertThat(json.get("sourceTime").asText()).isEqualTo("2026-07-28T10:15:30");
		assertThat(json.get("emittedAt").asText()).isEqualTo("2026-07-28T10:15:31");
		assertThat(json.has("sourceTradingDate")).isFalse();
	}
}
