// 반사실 시나리오 1건 — 같은 수량을 그 가격·그 시각에 팔았다면 수익률이 얼마였을지를 담는다.
package com.finplay.api.feedback.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절이고 가격 정의와 수익률 계산식은 spec
 * §반사실·집단 비교 계산이다. 세 시나리오({@code atClose}·{@code atHoldHigh}·{@code atFirstMoveAfterBuy})가
 * 같은 형태라 {@code Counterfactuals} 안에서 이 record를 세 번 쓴다.
 *
 * <p><b>가격은 전부 분봉 {@code close} 기준이다.</b> {@code high}로 잡으면 이 서비스에서 사용자가 애초에 얻을
 * 수 없었던 가격이 되고(시장가 체결이 직전 완료 분봉의 종가로만 이루어진다) 실현 불가능한 수익률로 후회를
 * 유도하는 셈이 된다(§파생 사실 계산).
 *
 * <p><b>{@code returnRate}는 수수료를 다시 계산해 산출한다</b> — 매도금액 비례라 가격이 바뀌면 수수료도 바뀐다
 * ({@code FLOOR(매도금액 × 시장별 요율)}, {@code OrderExecutionService}와 같은 식). {@code price}가 {@code null}인
 * 시나리오(보유 구간에 카드가 없는 {@code atFirstMoveAfterBuy})는 {@code returnRate}도 {@code null}이다.
 *
 * <p><b>이 값은 AI 서술에 넣지 않는다</b> — {@code PostSellPromptDto}에 해당 필드가 없는 이유이며
 * §왜 반사실은 AI 문장에 넣지 않는가에 근거가 있다. 구조화 필드로만 내려보내고 화면이 표로 그린다.
 */
public record CounterfactualScenario(BigDecimal price, LocalDateTime at, BigDecimal returnRate) {
}
