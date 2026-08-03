// 개장 전 브리핑 서술 프롬프트의 입력 — 시장 단위 기사 목록을 담는다.
package com.finplay.api.feedback.service;

import com.finplay.api.market.domain.Market;
import java.time.LocalDate;
import java.util.List;

/**
 * @param market        {@code STOCK}이면 범위가 §C-2의 `전장`이고, {@code CRYPTO}는 '개장 전'이 없어
 *                      최근 24시간이다 (FEED-009). 두 값에 따라 "시장:"·"범위:" 줄이 갈린다
 * @param referenceDate "전일"·"당일" 판정 기준. 주식은 원본 거래일, 코인은 배치 실행 시점의 KST 날짜다
 * @param items         상한은 {@code feedback.news.max-items-per-summary}이며 자르는 것은 호출부 책임이다
 */
public record MarketBriefingPromptDto(
	Market market,
	LocalDate referenceDate,
	List<BriefingNewsItemDto> items) {

	public MarketBriefingPromptDto {
		items = List.copyOf(items);
	}
}
