// SSE price 이벤트 페이로드 — 종목 하나의 가격이 변경될 때 전송한다 (주식: 매분 공개, 코인: 빗썸 틱마다) (이슈 #18)
package com.finplay.api.domain.market.dto.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketPriceEvent(
	Market market,
	String symbol,
	BigDecimal price,
	LocalDateTime sourceTime,
	LocalDateTime emittedAt,
	LocalDate sourceTradingDate,
	StockMarketStatus marketStatus) {

	// sourceTradingDate는 주식에서만 값을 가진다 — 코인은 null이며 클래스 레벨 @JsonInclude(NON_NULL)로 필드 자체가 생략된다 (plan.md SSE 계약).
}
