// 종목 뉴스 목록·요약 조회 응답 — 그 시점의 요약 한 건과 노출 가능한 기사 목록을 담는다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import java.time.LocalDate;
import java.util.List;

/**
 * 계약은 {@code docs/api-contracts.md}의 "종목 뉴스 목록·요약 조회" 행이고, 상태값과 판정 순서는 spec §C-4,
 * {@code items} 범위와 {@code summaryScope} 대응은 §C-2가 정본이다.
 *
 * <p><b>어느 상태든 200이다</b> (FEED-008). 비어 있는 것은 오류가 아니다.
 *
 * <p><b>{@code items}는 {@code NewsItem}을 그대로 쓴다</b> (§C-6). 종목이 경로에 있어 항목마다 종목을 붙일
 * 필요가 없다 — 시장 전체가 대상이라 종목명을 평평하게 갖는 Part D의 {@code BriefingNewsItem}과 다른 점이다.
 *
 * @param originTradeDate 주식은 현재 재생세션의 원본 거래일. <b>재생세션이 준비되지 않았으면 {@code null}</b>이다
 * @param summaryScope    그 조회 시각이 어느 범위의 요약을 보는지(§C-2). 개장 전에는 {@code null}이다
 * @param summaryStatus   판정 순서는 §C-4의 표 그대로다
 * @param summary         {@code READY}가 아니면 {@code null}이다
 * @param items           상한은 {@code feedback.news.max-items-per-news-list}이며 요약 프롬프트의 상한
 *                        ({@code max-items-per-summary})과 다른 값이다. 정렬은 발행시각 내림차순 + {@code id}
 *                        내림차순이고 절단은 §뉴스 매칭 범위의 공시 우선 규칙을 따른다
 */
public record InstrumentNewsResponse(
	LocalDate originTradeDate,
	NewsSummaryScope summaryScope,
	FeedbackContentStatus summaryStatus,
	String summary,
	List<NewsItem> items) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (docs/agent-mistakes.md 2026-07-29).
	public InstrumentNewsResponse {
		items = List.copyOf(items);
	}

	/**
	 * 아직 열리지 않은 응답 — 개장 전이거나 재생세션이 준비되지 않았다 (§C-4 판정 순서 1·2번).
	 *
	 * @param originTradeDate 재생세션 미준비이면 {@code null}, 개장 전이면 원본 거래일을 채운다. <b>둘의 차이가
	 *     의도된 것이다</b> — 미준비 상태에서는 어떤 거래일을 재생 중인지 자체가 확정되지 않았다
	 */
	public static InstrumentNewsResponse notYet(LocalDate originTradeDate) {
		return new InstrumentNewsResponse(
			originTradeDate, null, FeedbackContentStatus.NOT_YET, null, List.of());
	}

	public static InstrumentNewsResponse of(
		LocalDate originTradeDate,
		NewsSummaryScope summaryScope,
		FeedbackContentStatus summaryStatus,
		String summary,
		List<NewsItem> items) {
		return new InstrumentNewsResponse(originTradeDate, summaryScope, summaryStatus, summary, items);
	}
}
