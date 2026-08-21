// 대본이 안내하는 가격 변동 범위(low~high)를 담는 응답 DTO
package com.finplay.api.domain.education.marketpractice.dto.response;

import java.math.BigDecimal;

/**
 * 사건이 하나라도 있는 대본에서는 {@code null}이다(SCENARIO-015·020 보호). 대본을 쓰지 않는 attempt
 * (생성기 버전 1·완료 replay)도 {@code null}이다 — 기존 네 필드와 같은 규칙이다.
 */
public record PriceGuideRangeResponse(BigDecimal low, BigDecimal high) {
}
