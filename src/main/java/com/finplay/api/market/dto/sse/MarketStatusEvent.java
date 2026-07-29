// SSE status 이벤트 페이로드 — 시장 개장·마감, 코인 stale·연결 끊김/복구, 주식 데이터 준비 실패 등 상태 변화를 전달한다 (이슈 #18)
package com.finplay.api.market.dto.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.market.service.StockMarketStatus;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketStatusEvent(
	Market market,
	String symbol,
	StockMarketStatus marketStatus,
	PriceStatus status,
	String reason,
	LocalDateTime emittedAt) {

	// symbol·status·reason은 시장 전체 상태 변화(예: 장 마감)에서는 null일 수 있다 — 클래스 레벨 @JsonInclude(NON_NULL)로 필드 자체가 생략된다.
}
