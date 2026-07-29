// MarketSnapshotEvent의 JSON 직렬화 계약(sourceTime/emittedAt/sourceTradingDate 필드 구분, 코인은 sourceTradingDate 생략)을 검증하는 단위 테스트다.
package com.finplay.api.market.dto.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketSnapshotEventTest {

	private final ObjectMapper objectMapper = new ObjectMapper()
		.registerModule(new JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	@Test
	void serializesStockSnapshotWithSourceTradingDateAndPricesIncludingUnavailableEntry() throws Exception {
		LocalDate sourceTradingDate = LocalDate.of(2026, 7, 24);
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 0, 5);
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 9, 1, 0);
		MarketSnapshotEvent event = MarketSnapshotEvent.of(
			Market.STOCK, sourceTradingDate, StockMarketStatus.OPEN, emittedAt,
			List.of(
				InstrumentPriceSnapshot.of("005930", BigDecimal.valueOf(70100), sourceTime, PriceStatus.AVAILABLE),
				InstrumentPriceSnapshot.of("000660", null, null, PriceStatus.UNAVAILABLE)));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asText()).isEqualTo("STOCK");
		assertThat(json.get("sourceTradingDate").asText()).isEqualTo("2026-07-24");
		assertThat(json.get("marketStatus").asText()).isEqualTo("OPEN");
		assertThat(json.get("emittedAt").asText()).isEqualTo("2026-07-28T10:00:05");
		assertThat(json.get("prices")).hasSize(2);
		assertThat(json.get("prices").get(0).get("symbol").asText()).isEqualTo("005930");
		assertThat(json.get("prices").get(0).get("price").asInt()).isEqualTo(70100);
		assertThat(json.get("prices").get(0).get("sourceTime").asText()).isEqualTo("2026-07-28T09:01:00");
		assertThat(json.get("prices").get(0).get("status").asText()).isEqualTo("AVAILABLE");
		// 가격 없는 종목도 배열에서 빠지지 않고 price·sourceTime=null로 명시적으로 포함된다 (배열은 배제되지 않음, JsonInclude는 최상위 필드에만 적용).
		assertThat(json.get("prices").get(1).get("symbol").asText()).isEqualTo("000660");
		assertThat(json.get("prices").get(1).get("price").isNull()).isTrue();
		assertThat(json.get("prices").get(1).get("sourceTime").isNull()).isTrue();
		assertThat(json.get("prices").get(1).get("status").asText()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void serializesCryptoSnapshotOmitsSourceTradingDateFieldEntirely() throws Exception {
		// 코인은 marketStatus(OPEN 고정)는 갖되 sourceTradingDate 개념이 없다 — null이면 클래스 레벨
		// @JsonInclude(NON_NULL)로 필드 자체가 생략되는지 확인한다 (marketStatus는 값이 있어 계속 노출됨과 대비).
		LocalDateTime emittedAt = LocalDateTime.of(2026, 7, 28, 10, 0, 5);
		MarketSnapshotEvent event = MarketSnapshotEvent.of(
			Market.CRYPTO, null, StockMarketStatus.OPEN, emittedAt,
			List.of(InstrumentPriceSnapshot.of("BTC", BigDecimal.valueOf(95000000),
				LocalDateTime.of(2026, 7, 28, 10, 0, 0), PriceStatus.AVAILABLE)));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

		assertThat(json.get("market").asText()).isEqualTo("CRYPTO");
		assertThat(json.has("sourceTradingDate")).isFalse();
		assertThat(json.get("marketStatus").asText()).isEqualTo("OPEN");
		assertThat(json.get("emittedAt").asText()).isEqualTo("2026-07-28T10:00:05");
	}
}
