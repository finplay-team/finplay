// 변동 원인 카드 서술 프롬프트의 입력 — 종목·구간·변동률과 그 시각의 근거 기사를 담는다.
package com.finplay.api.domain.feedback.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 주식·코인 카드가 같은 프롬프트를 쓴다. 시각은 전부 원본 거래일 시간축이다(spec §C-2).
 *
 * <p>{@code openingGap}이 {@code true}면 시가 갭 카드다. 갭 카드는 구간이 없고 근거가 전장 기사이므로
 * 구간 줄과 기사 머리말이 갈린다. 엔티티의 {@code event_type} 열거형은 #4 소유라 boolean으로 받는다.
 *
 * @param windowStart 장중 카드의 구간 시작. 코인은 {@code occurredAt − rolling-window-minutes}이고,
 *                    갭 카드에서는 쓰이지 않으므로 {@code null}이어도 된다
 * @param windowEnd   장중 카드의 구간 끝. 코인은 {@code occurredAt}이다. 갭 카드에서는 쓰이지 않는다
 * @param windowMinutes 구간 길이(분). <b>{@code windowStart}·{@code windowEnd}의 차로 다시 계산하지 않는다</b> —
 *                      둘은 {@code LocalTime}이라 코인 카드가 자정을 넘으면(예: {@code 23:58 ~ 00:03}, spec §C-9)
 *                      차가 음수가 되고 "{@code -1435분간}" 같은 문장이 예외 없이 사용자에게 나간다.
 *                      절대 시각을 가진 호출부(주식은 원본 거래일, 코인은 {@code occurredAt})가 계산해 넣는다.
 *                      갭 카드에서는 쓰이지 않는다
 * @param changeRate  비율 그대로 넣는다 (-0.0182 → -1.82%). 서버가 계산한 값이며 모델이 고치지 않는다
 * @param referenceDate 원본 거래일. 근거 기사가 그 전날 것이면 프롬프트에 "전일"을 붙이는 데만 쓴다.
 *                      코인은 탐지 시각의 KST 날짜를 넣는다
 * @param sources     근거 기사. 비어 있을 수 없다 — 근거가 0건이면 카드 자체를 만들지 않는다(FEED-003)
 */
public record PriceMovePromptDto(
	String instrumentName,
	boolean openingGap,
	LocalTime windowStart,
	LocalTime windowEnd,
	int windowMinutes,
	BigDecimal changeRate,
	LocalDate referenceDate,
	List<NewsSourceDto> sources) {

	// 컬렉션 필드를 가진 record는 방어적 복사를 넣지 않으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (ai/agent-mistakes.md 2026-07-29). 이하 record 전부 같은 이유로 compact 생성자를 둔다.
	public PriceMovePromptDto {
		sources = List.copyOf(sources);
	}
}
