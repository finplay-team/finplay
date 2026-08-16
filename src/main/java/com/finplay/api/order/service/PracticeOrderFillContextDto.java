// 지정가 체결이 attempt 선잠금 뒤 사용하는 현재 run 여부와 canonical 체결가
package com.finplay.api.order.service;

import java.math.BigDecimal;

public record PracticeOrderFillContextDto(boolean currentRun, BigDecimal canonicalPrice) {
}
