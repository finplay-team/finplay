// 매도 회고 프롬프트에 들어갈 "보유 구간에 걸친 변동 카드" 1건 — 매수·매도와의 시간 간격을 함께 담는다.
package com.finplay.api.feedback.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code minutesAfterBuy}·{@code minutesBeforeSell}이 이 record의 존재 이유다. 수치를 다시 읽어주는 것은
 * 조회일 뿐이고, 매수·매도가 변동과 어떤 순서였는지가 피드백이다 (spec §파생 사실 계산).
 *
 * <p><b>구간 시각을 날짜까지 담는다</b>(이슈 #275). {@link PostSellPromptDto}가 매수·매도 시각을 넓힌 것과
 * 같은 이유다 — 코인은 보유가 며칠에 걸치는 경우가 흔한데 카드만 {@code HH:mm}으로 남으면 <b>"8월 1일 매수 /
 * 8월 5일 매도"와 "10:30~11:00 변동"이 한 프롬프트에 섞여</b> 그 카드가 어느 날 것인지 알 수 없다. 프롬프트
 * 마지막 줄이 하필 "매수·매도 시각이 변동·기사와 어떤 순서였는지를 중심으로 써줘"다. 날짜를 실제로 쓸지는
 * {@code PostSellPromptDto.multiDayHold}가 정하므로 <b>주식 문장은 달라지지 않는다.</b>
 *
 * @param minutesAfterBuy   카드 {@code windowEnd} − 매수시각 (분)
 * @param minutesBeforeSell 매도시각 − 카드 {@code windowEnd} (분)
 */
public record HeldPriceMoveDto(
	LocalDateTime windowStart,
	LocalDateTime windowEnd,
	BigDecimal changeRate,
	int minutesAfterBuy,
	int minutesBeforeSell,
	List<NewsSourceDto> sources) {

	public HeldPriceMoveDto {
		sources = List.copyOf(sources);
	}
}
