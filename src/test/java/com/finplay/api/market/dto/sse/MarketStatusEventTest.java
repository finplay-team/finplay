// MarketStatusEvent의 JSON 직렬화 계약(종목별 상태 변화 vs 시장 전체 상태 변화에서 null 필드 생략)을 검증하는 @JsonTest다.
package com.finplay.api.market.dto.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.market.service.StockMarketStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class MarketStatusEventTest {

	// 실제 SSE 전송이 쓰는 것과 같은, Boot의 JacksonAutoConfiguration이 구성한 빈을 주입받는다 (ADR-0003 — 직렬화는 슬라이스에서 실제로 확인).
	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void serializesInstrumentLevelStatusEventWithSymbolAndStatusPresent() {
		// 코인 웹소켓 연결 끊김 등 종목 단위 상태 변화는 symbol·status·reason이 모두 채워진다 — reason은
		// 036-remove-crypto-stale-status 이후에도 여전히 원본 연결상태 이름(FeedConnectionStatus)을 담는다.
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
		// 장 마감 같은 시장 전체 상태 변화는 symbol·status·reason이 없다 — null이면 필드 자체가 생략되어야 한다.
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
