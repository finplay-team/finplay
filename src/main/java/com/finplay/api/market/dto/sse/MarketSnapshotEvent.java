// SSE snapshot 이벤트 페이로드 — 구독 시작 직후 해당 market 전체 종목의 현재 시세를 배열 1건으로 전달한다 (이슈 #18)
package com.finplay.api.market.dto.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketSnapshotEvent(
	Market market,
	LocalDate sourceTradingDate,
	StockMarketStatus marketStatus,
	LocalDateTime emittedAt,
	List<InstrumentPriceSnapshot> prices) {

	// sourceTradingDate는 주식에서만 값을 가진다 — 코인은 null이며 클래스 레벨 @JsonInclude(NON_NULL)로 필드 자체가 생략된다 (plan.md SSE 계약).
	public static MarketSnapshotEvent of(
		Market market,
		LocalDate sourceTradingDate,
		StockMarketStatus marketStatus,
		LocalDateTime emittedAt,
		List<InstrumentPriceSnapshot> prices) {
		return new MarketSnapshotEvent(market, sourceTradingDate, marketStatus, emittedAt, prices);
	}

	// snapshot 배열 안의 종목 1건 — 가격이 없는 종목도 배열에서 빼지 않고 price·sourceTime=null, status=UNAVAILABLE로 포함한다.
	public record InstrumentPriceSnapshot(String symbol, BigDecimal price, LocalDateTime sourceTime,
		PriceStatus status) {

		public static InstrumentPriceSnapshot of(String symbol, BigDecimal price, LocalDateTime sourceTime,
			PriceStatus status) {
			return new InstrumentPriceSnapshot(symbol, price, sourceTime, status);
		}
	}
}
