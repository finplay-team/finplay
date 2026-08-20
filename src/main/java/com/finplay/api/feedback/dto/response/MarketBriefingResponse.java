// 개장 전 브리핑 조회 응답 — 시장 단위 요약 한 건과 그 구간의 기사 목록을 담는다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.market.domain.Market;
import java.time.LocalDate;
import java.util.List;

/**
 * 계약은 {@code docs/api/feedback.md}의 "개장 전 브리핑 조회" 행이고, 상태값과 판정 순서는 spec §C-4,
 * 구간은 §C-2의 {@code 전장}, 공시 날짜 판정은 §C-3이 정본이다.
 *
 * <p><b>어느 상태값이든 200이다</b>(FEED-009). 비어 있는 것은 오류가 아니다 — 특히 <b>배포 직후 이틀은
 * 비어 있을 수 있다.</b> 근거 구간이 과거 17.5시간이라 수집 이력이 쌓여야 한다.
 *
 * <p><b>Part C와 상태값이 갈리는 자리가 하나 있다</b>(§C-4 판정 순서 1번). 재생세션 미준비일 때 Part C는
 * {@code NOT_YET}이지만 Part D는 {@code EMPTY}다 — Part D는 "브리핑이 아예 없는 날"이 정상이고 Part C는
 * "아직 열리지 않았다"가 맞기 때문이며, 의도된 차이다.
 *
 * @param market          요청한 시장. 요청 값을 그대로 되돌려 준다
 * @param originTradeDate 주식은 현재 재생세션의 원본 거래일. <b>재생세션이 준비되지 않았으면 {@code null}</b>이고,
 *                        코인도 '거래일 경계'가 없어 {@code null}이다
 * @param status          판정 순서는 §C-4의 표 그대로다
 * @param summary         {@code READY}가 아니면 {@code null}이다
 * @param items           <b>저장하지 않고 조회 시 같은 구간 질의로 다시 만든다</b>(FEED-009). 상한은
 *                        {@code feedback.news.max-items-per-briefing}이며 브리핑 프롬프트의 상한
 *                        ({@code max-items-per-summary})과 <b>다른 값이다.</b> 정렬은 발행시각 내림차순 +
 *                        {@code id} 내림차순이고 절단은 §뉴스 매칭 범위의 공시 우선 규칙을 따른다
 */
public record MarketBriefingResponse(
	Market market,
	LocalDate originTradeDate,
	FeedbackContentStatus status,
	String summary,
	List<BriefingNewsItem> items) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (ai/agent-mistakes.md 2026-07-29).
	public MarketBriefingResponse {
		items = List.copyOf(items);
	}

	/**
	 * 목록도 요약도 없는 응답 (§C-4 판정 순서 1·2번).
	 *
	 * @param originTradeDate 재생세션 미준비·코인이면 {@code null}, 개장 전이면 원본 거래일을 채운다
	 * @param status          재생세션 미준비는 {@code EMPTY}, 개장 전은 {@code NOT_YET}이다 — 순서를 바꾸면
	 *     두 조건이 동시에 성립하는 구간(00:00~08:40)에서 값이 갈린다
	 */
	public static MarketBriefingResponse withoutItems(
		Market market, LocalDate originTradeDate, FeedbackContentStatus status) {
		return new MarketBriefingResponse(market, originTradeDate, status, null, List.of());
	}

	public static MarketBriefingResponse of(
		Market market,
		LocalDate originTradeDate,
		FeedbackContentStatus status,
		String summary,
		List<BriefingNewsItem> items) {
		return new MarketBriefingResponse(market, originTradeDate, status, summary, items);
	}
}
