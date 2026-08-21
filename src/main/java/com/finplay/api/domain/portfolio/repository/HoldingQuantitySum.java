// holding 단위 수량 합계 조회 결과 — 매수 lot 합계·매도 배분 합계 각각에 재사용하는 중간값
package com.finplay.api.domain.portfolio.repository;

import java.math.BigDecimal;

/**
 * spec 012 §반사실·집단 비교 계산 [집단 비교] 블록의 lot·배분 재구성 식에서, holding 하나가 특정 시점까지
 * 누적한 수량 합을 나른다. {@code holdingId}는 회원 식별자가 아니라 {@code holdings} 행의 PK다 — 이 값은
 * {@code HolderPopulationQueryService} 내부에서만 두 합계를 짝지어 순가감을 계산하는 데 쓰이고 서비스 밖으로
 * 나가지 않는다.
 */
public record HoldingQuantitySum(Long holdingId, BigDecimal quantity) {
}
