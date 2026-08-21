// 3단계 evidence 판정에 쓰는 참조 손절가·익절가 절대 가격선을 담는 계산 결과 DTO
package com.finplay.api.domain.education.marketpractice.service;

import java.math.BigDecimal;

public record ReferencePriceLines(BigDecimal referenceStopLossPrice, BigDecimal referenceTakeProfitPrice) {
}
