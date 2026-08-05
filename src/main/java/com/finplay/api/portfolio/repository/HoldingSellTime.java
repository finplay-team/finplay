// holding 단위로 시점 T 이후 첫 매도 체결 시각을 나르는 조회 결과 — 매도까지 걸린 시간 계산의 중간값
package com.finplay.api.portfolio.repository;

import java.time.LocalDateTime;

/**
 * spec 012 §반사실·집단 비교 계산 [집단 비교] 블록의 "T 시점 이후 첫 매도" 계산에서, holding 하나가 시점
 * {@code T} 뒤에 처음 그 종목을 매도한 체결의 {@code executed_at}을 나른다. {@code holdingId}는 회원 식별자가
 * 아니라 {@code holdings} 행의 PK다 — {@code HolderPopulationQueryService} 내부에서만 T 시점 모집단 판정과
 * 짝지어 "매도까지 걸린 분"을 계산하는 데 쓰이고 서비스 밖으로 나가지 않는다.
 */
public record HoldingSellTime(Long holdingId, LocalDateTime firstSellExecutedAt) {
}
