// OCO 예약에 영속할 기준 시세 snapshot — 어느 경로에서 왔는지와 무관한 값 한 쌍
package com.finplay.api.order.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 일반·교육 경로는 {@code PriceQueryService}가 확정한 서버 유효 현재가에서, 튜토리얼 자동 예약 경로는
 * 041 대본의 canonical price에서 온다. 두 출처를 한 자리에서 합쳐 {@code newExitPlan}이 출처를 몰라도
 * 되게 한다.
 */
public record ExitPlanBaselineDto(BigDecimal price, LocalDateTime observedAt) {
}
