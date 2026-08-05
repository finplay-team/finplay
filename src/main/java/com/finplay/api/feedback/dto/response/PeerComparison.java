// 같은 변동 구간을 겪은 다른 회원들의 행동 분포 — 회원을 식별할 수 있는 값은 어떤 형태로도 담지 않는다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import java.math.BigDecimal;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절, 상태값은 spec §C-4, 게이트는 §C-5,
 * 지표 계산은 §반사실·집단 비교 계산이다.
 *
 * <p><b>게이트가 시각이 아니라 확정 집계 행의 존재다</b>(§C-5). 장 마감 집계 배치가 게이트 시각보다 늦게 돌기
 * 때문에, 시각으로 두면 그 사이 조회가 게이트만 통과하고 값은 비는 상태가 된다.
 *
 * <p>{@code priceMoveId}는 기준 카드다 — 보유 구간 안의 <b>첫</b> 변동 카드 하나이며
 * {@code Counterfactuals.atFirstMoveAfterBuy}와 같은 카드다. {@code yourMinutesToSell}은 모집단 통계가 아니라
 * 본인 값({@code 매도시각 − 카드 windowEnd})이라 {@code INSUFFICIENT_SAMPLE}에서도 채운다(§C-4).
 *
 * <p><b>회원 ID·닉네임·개별 체결을 담지 않는다</b>(FEED-011). 모집단 재구성 자체가 회원 식별자 없는 반환이다.
 *
 * <p><b>{@code sameSessionCompleted=false}이면 이 record 자체가 {@code null}이다</b> — {@code status}만 담은
 * 껍데기를 내리지 않는다(계약이 이미 정한 형태).
 *
 * <p>이슈 #208은 {@code status}를 상수 {@code NOT_YET}으로 두고 지표 전부를 {@code null}로 둔다(3번 항목).
 * 확정 집계 행 기준 판정({@code NO_EVENT} 1순위)과 지표 계산은 {@code plan.md} 7번이다.
 */
public record PeerComparison(
	PostSellFeedbackStatus status,
	Long priceMoveId,
	Integer holderCount,
	BigDecimal soldWithin30MinRate,
	Integer medianMinutesToSell,
	Integer yourMinutesToSell) {
}
