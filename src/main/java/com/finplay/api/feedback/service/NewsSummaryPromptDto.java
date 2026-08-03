// 종목 뉴스 요약 서술 프롬프트의 입력 — 종목·범위와 그 범위의 기사 목록을 담는다.
package com.finplay.api.feedback.service;

import java.time.LocalDate;
import java.util.List;

/**
 * @param scope         프롬프트의 "범위:" 줄을 결정한다. 주식은 {@code PRE_MARKET}·{@code FULL} 두 건을
 *                      만들고 코인은 {@code ROLLING_24H} 하나뿐이다 (FEED-008)
 * @param referenceDate "전일"·"당일"을 판정하는 기준 날짜다. 주식은 원본 거래일, 코인은 배치 실행 시점의
 *                      KST 날짜를 넣는다 — 코인 요약 행이 {@code origin_trade_date}에 저장하는 값과 같다(§C-9)
 * @param items         요약에 넣을 기사·공시. 상한은 {@code feedback.news.max-items-per-summary}이며
 *                      자르는 것은 호출부 책임이다 — 이 record는 받은 목록을 그대로 적는다
 */
public record NewsSummaryPromptDto(
	String instrumentName,
	NewsSummaryScope scope,
	LocalDate referenceDate,
	List<NewsSourceDto> items) {

	public NewsSummaryPromptDto {
		items = List.copyOf(items);
	}
}
