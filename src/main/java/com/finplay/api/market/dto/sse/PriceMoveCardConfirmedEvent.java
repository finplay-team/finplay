// Redis 메시지이자 SSE priceMoveCardConfirmed 이벤트 페이로드 — 카드 본문 없이 식별 정보만 담는다 (ADR-0018 §결정)
package com.finplay.api.market.dto.sse;

import com.finplay.api.market.domain.Market;
import java.time.LocalDateTime;

public record PriceMoveCardConfirmedEvent(
	Market market,
	Long instrumentId,
	Long priceMoveEventId,
	LocalDateTime emittedAt) {
}
