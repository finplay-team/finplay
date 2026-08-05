// 코인 시세가 최신값으로 갱신됐음을 알리는 이벤트 (015-limit-order LMT-002 체결 트리거)
package com.finplay.api.market.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CryptoPriceUpdatedEvent(String symbol, BigDecimal price, LocalDateTime receivedAt) {
}
