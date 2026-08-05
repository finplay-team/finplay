// PriceStore가 코인 가격 스냅샷 조회 결과로 돌려주는 (기록 시각, 가격) 값 객체 (spec 012 §코인 가격 스냅샷)
package com.finplay.api.market.store;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceSnapshotDto(LocalDateTime recordedAt, BigDecimal price) {
}
