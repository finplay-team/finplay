// 코인 가상 가격 세션이 tick 한 칸 진행했음을 알리는 전용 이벤트 — 일반 CryptoPriceUpdatedEvent와 타입을 공유하지 않는다(plan.md)
package com.finplay.api.education.priceruntime.event;

import java.math.BigDecimal;

public record PracticePriceTickAdvancedEvent(
	Long sessionId,
	// userId·instrumentId는 현재 PracticeTickFillListener가 읽지 않는다(체결 격리는 sessionId만으로 충분).
	// 이슈 #320 1안에서 확정된 이벤트 페이로드 형태이며, holding 관찰 세션 역추적(030 tasks.md 4번째)·SSE
	// 등 세션 조회 없이 바로 필요한 후속 리스너를 위해 유지한다.
	Long userId,
	Long instrumentId,
	int tick,
	BigDecimal price,
	boolean lastTick) {
}
