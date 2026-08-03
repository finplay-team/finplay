// 매도 회고 프롬프트에 들어갈 "보유 구간에 걸친 변동 카드" 1건 — 매수·매도와의 시간 간격을 함께 담는다.
package com.finplay.api.feedback.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * {@code minutesAfterBuy}·{@code minutesBeforeSell}이 이 record의 존재 이유다. 수치를 다시 읽어주는 것은
 * 조회일 뿐이고, 매수·매도가 변동과 어떤 순서였는지가 피드백이다 (spec §파생 사실 계산).
 *
 * @param minutesAfterBuy   카드 {@code windowEnd} − 매수시각 (분)
 * @param minutesBeforeSell 매도시각 − 카드 {@code windowEnd} (분)
 */
public record HeldPriceMoveDto(
	LocalTime windowStart,
	LocalTime windowEnd,
	BigDecimal changeRate,
	int minutesAfterBuy,
	int minutesBeforeSell,
	List<NewsSourceDto> sources) {

	public HeldPriceMoveDto {
		sources = List.copyOf(sources);
	}
}
