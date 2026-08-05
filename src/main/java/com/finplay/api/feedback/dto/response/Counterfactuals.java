// 같은 수량을 다른 시점에 팔았다면 어땠을지를 담는 반사실 세 묶음 — 장 마감 게이트를 통과한 뒤에만 채워진다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.PostSellFeedbackStatus;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절, 게이트는 spec §C-5({@code postSellFlow}와
 * 같다), 가격 정의는 §반사실·집단 비교 계산이다.
 *
 * <p>세 시나리오의 가격은 각각 그 거래일 <b>마지막 분봉</b>의 close, 보유 구간 최고가, <b>보유 구간(매수~매도)</b>
 * 안의 첫 변동 카드 {@code windowEnd}의 종가다. 보유 구간에 카드가 없으면 {@code atFirstMoveAfterBuy}가
 * {@code null}이고 <b>매도 이후의 카드는 쓰지 않는다</b> — 보유하지 않은 구간이다.
 *
 * <p><b>{@code sameSessionCompleted=false}이면 이 record 자체가 {@code null}이다</b> — {@code status}만 담은
 * 껍데기를 내리지 않는다(계약이 이미 정한 형태). 분봉이 불연속이라 계산이 성립하지 않는다.
 *
 * <p>판정과 세 시나리오의 {@code price}·{@code at}을 채우는 것은 이 이슈의 3번 항목이고, 각 시나리오의
 * {@code returnRate}는 {@code plan.md} 7번이 수수료를 재계산해 채운다.
 */
public record Counterfactuals(
	PostSellFeedbackStatus status,
	CounterfactualScenario atClose,
	CounterfactualScenario atHoldHigh,
	CounterfactualScenario atFirstMoveAfterBuy) {
}
