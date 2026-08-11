// 코인 가상 가격 세션이 tick 한 칸 진행했음을 알리는 전용 이벤트 — 일반 CryptoPriceUpdatedEvent와 타입을 공유하지 않는다(plan.md)
package com.finplay.api.education.priceruntime.event;

import java.math.BigDecimal;

public record PracticePriceTickAdvancedEvent(
	Long sessionId,
	Long userId,
	Long instrumentId,
	int tick,
	BigDecimal price,
	boolean lastTick) {
}
