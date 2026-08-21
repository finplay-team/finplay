// 코인 시세가 최신값으로 갱신됐음을 알리는 이벤트 (015-limit-order LMT-002 체결 트리거)
package com.finplay.api.domain.market.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// receivedAt(체결 시각)은 표시용 sourceTime 의미이고, observedAt(관측 시각)은 이 이벤트가 발행될 때마다 항상
// 새로 갱신되는 값이다 — receivedAt은 웹소켓 경로에서만 새 값을 받고 REST 폴러(recordObservation)는 절대
// 갱신하지 않으므로, 서로 다른 발행 건을 구분해야 하는 소비자(SSE id 등)는 receivedAt이 아니라 observedAt을
// 써야 한다(034-crypto-price-rest-backup, SSE id 발행 충돌 회귀 수정).
public record CryptoPriceUpdatedEvent(String symbol, BigDecimal price, LocalDateTime receivedAt,
	LocalDateTime observedAt) {
}
