// 매도 회고의 보유 구간 극값 묶음 — 주식·코인 두 조립 경로가 함께 쓰는 중간값이다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.HoldHighBasis;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 계산 규칙은 spec §파생 사실 계산({@code [보유 구간 극값]})이고 코인의 정밀도 분기는 §FEED-012 결정 4다.
 *
 * <p><b>응답 DTO가 아니라 조립 중간값이라 {@code dto/response/}에 두지 않는다</b>(§C-6에 이 이름이 없는
 * 이유다). {@link PostSellFeedbackReader}와 {@link CryptoPostSellFeedbackReader}가 각자 채워 응답에 흩어
 * 담는다.
 *
 * <p><b>여섯 값은 함께 있거나 함께 없다.</b> 개별 {@code null}로 흩뜨리면 "극값은 있는데 비율만 빈" 조합이
 * 표현 가능해지고, 그 상태가 응답에 나가면 화면이 극값 시각은 그리면서 매도가와의 거리는 못 그린다.
 *
 * @param basis 극값을 <b>어느 표본으로 쟀는지</b>. 주식은 언제나 {@link HoldHighBasis#MINUTE}이고, 코인은 보유
 *     구간이 199분을 넘으면 {@link HoldHighBasis#DAILY}다. <b>{@link #absent()}에서는 {@code null}이다</b> —
 *     잰 값이 없는데 정밀도만 남으면 화면이 "근사값이 있다"로 읽는다
 */
record HoldExtremes(
	BigDecimal holdHighPrice,
	LocalDateTime holdHighAt,
	BigDecimal holdLowPrice,
	LocalDateTime holdLowAt,
	BigDecimal sellVsHighRate,
	BigDecimal sellVsLowRate,
	HoldHighBasis basis) {

	/** 극값을 구할 수 없을 때. 계약이 정한 전부 {@code null}이다 — {@code basis}도 포함이다. */
	static HoldExtremes absent() {
		return new HoldExtremes(null, null, null, null, null, null, null);
	}
}
