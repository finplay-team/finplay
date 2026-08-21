// 매도 체결에 배분된 매수 체결 1건 — 체결 id와 그 체결시각만 담는 읽기 전용 내부 DTO.
package com.finplay.api.domain.portfolio.service;

import java.time.LocalDateTime;

/**
 * spec 012 §C-6이 {@code portfolio}에 요구한 "배분된 매수 체결 목록"의 항목이다 — {@code feedback}의 투자일기
 * 반영(4차, §FEED-013 결정 4)이 쓴다.
 *
 * <p><b>엔티티 참조가 없다.</b> {@link SellAllocationSummaryDto}와 같은 성격이며, 조회 트랜잭션 밖에서 열어도
 * {@code LazyInitializationException}이 나지 않는다.
 *
 * @param buyTradeId 매수 체결 id. 이 id로 {@code journal}이 매수 회고를 찾는다
 * @param executedAt 그 매수의 체결시각 — 배분 조회의 <b>정렬 키 자체</b>다({@code holding_lots.executed_at}).
 *     프롬프트의 일기 줄머리 시각이 이 값이며, <b>여기서 포맷하지 않고 {@code LocalDateTime} 그대로 넘긴다</b> —
 *     시·분만 적을지 날짜까지 적을지는 보유가 하루를 넘겼는지({@code multiDayHold})가 정하고 그 판단 재료는
 *     프롬프트 조립부에 있다(이슈 #275)
 */
public record AllocatedBuyTradeDto(Long buyTradeId, LocalDateTime executedAt) {
}
