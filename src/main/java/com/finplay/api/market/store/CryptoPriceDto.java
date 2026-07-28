// Redis에 저장된 코인 심볼의 최신 가격·수신시각을 PriceStore가 호출자에게 전달한다.
package com.finplay.api.market.store;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CryptoPriceDto(String symbol, BigDecimal price, LocalDateTime receivedAt) {
}
