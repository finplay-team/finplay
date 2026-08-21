// 배치가 만들어 두는 콘텐츠(요약·브리핑)를 조회 시점에 쓸 수 있는지 나타내는 상태값 — 저장하지 않고 조회 때 판정한다.
package com.finplay.api.domain.feedback.entity;

/**
 * 값과 <b>판정 순서</b>의 정본은 spec §C-4다. Part C의 {@code summaryStatus}와 Part D의 {@code status}가 같은
 * 4종을 쓰므로(§C-4 "같은 4종") 열거형을 하나만 둔다.
 *
 * <p><b>컬럼이 아니다.</b> {@code instrument_news_summaries}·{@code market_briefings}에는 이 값을 담는 열이
 * 없고(§C-8), 저장된 행만으로는 {@code EMPTY}와 {@code UNAVAILABLE}이 구분되지 않는다 — 둘 다
 * {@code summary}가 {@code NULL}이다. 그래서 조회 시 <b>행 존재 여부와 {@code items} 개수</b>로 판정한다.
 *
 * <p><b>어느 값이든 상태코드는 200이다</b> (FEED-008·FEED-009). 비어 있는 것은 오류가 아니다.
 */
public enum FeedbackContentStatus {

	/** 행이 있고 서술이 있다. */
	READY,

	/**
	 * 주식이 아직 열리지 않았다 — 개장 전이거나 재생세션이 준비되지 않았다.
	 *
	 * <p><b>코인에는 이 값이 없다</b> (FEED-008·FEED-009). 24시간 거래라 '개장 전'이라는 시점이 없고
	 * 재생세션과도 무관하다.
	 */
	NOT_YET,

	/**
	 * 대상 기사·공시가 0건이거나, <b>요약·브리핑 행이 아직 없다</b>(배치 미실행·배포 당일).
	 *
	 * <p>두 경우로 {@code items}가 갈린다 — 기사가 0건이면 당연히 비고, 행만 없는 경우에는 <b>채운다.</b>
	 */
	EMPTY,

	/**
	 * 행은 있는데 서술이 없다 — LLM 호출이 실패했거나 후검증 재생성 후에도 금지 표현이 남았다
	 * ({@code narrative_source = NONE}).
	 *
	 * <p>{@code items}는 채운다. 기사는 있는데 문장만 못 만든 상태이므로 {@code EMPTY}와 구분된다.
	 */
	UNAVAILABLE
}
