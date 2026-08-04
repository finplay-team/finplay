// 매도 직후 피드백 응답의 묶음별(매도 후 흐름·반사실·집단 비교·서술) 노출 상태값 — 저장하지 않고 조회 시 판정한다.
package com.finplay.api.feedback.domain;

/**
 * 값의 정본은 spec §C-4이고 게이트는 §C-5다. Part B의 네 자리
 * ({@code postSellFlow.status}·{@code counterfactuals.status}·{@code peerComparison.status}·
 * {@code narrativeStatus})가 이 하나를 함께 쓴다 — {@code FeedbackContentStatus}가 Part C의
 * {@code summaryStatus}와 Part D의 {@code status}를 하나로 둔 것과 같은 이유다. <b>자리마다 나오는 값의 범위가
 * 다른 것은 판정 로직의 차이이고 타입의 차이가 아니다</b>(§C-6의 {@code FeedbackContentStatus} 문단).
 *
 * <table>
 * <caption>자리별로 실제로 나올 수 있는 값 (§C-4)</caption>
 * <tr><th>자리</th><th>값</th></tr>
 * <tr><td>{@code postSellFlow.status}</td><td>{@code READY} · {@code NOT_YET}</td></tr>
 * <tr><td>{@code counterfactuals.status}</td><td>{@code READY} · {@code NOT_YET}</td></tr>
 * <tr><td>{@code peerComparison.status}</td>
 *     <td>{@code READY} · {@code INSUFFICIENT_SAMPLE} · {@code NO_EVENT} · {@code NOT_YET}</td></tr>
 * <tr><td>{@code narrativeStatus}</td><td>{@code READY} 뿐</td></tr>
 * </table>
 *
 * <p><b>{@code FeedbackContentStatus}를 재사용하지 않는다.</b> 그 열거형에는 {@code INSUFFICIENT_SAMPLE}·
 * {@code NO_EVENT}가 없고, 반대로 이 자리에는 {@code EMPTY}·{@code UNAVAILABLE}이 없다 — 매도 회고는 템플릿
 * 문장이 있어 서술이 비지 않으므로 {@code UNAVAILABLE}이 <b>존재하지 않는다</b>(§C-4).
 *
 * <p><b>어느 값이든 상태코드는 200이다</b>(FEED-007). 아직 열리지 않은 것은 오류가 아니다.
 */
public enum PostSellFeedbackStatus {

	/** 게이트를 통과했고 값이 채워졌다. */
	READY,

	/**
	 * 아직 열리지 않았다 — 장 마감(그 체결의 서비스 날짜 15:30) 전이거나 확정 집계 행이 없다(§C-5).
	 *
	 * <p><b>기준 날짜가 "오늘"이 아니라 그 체결의 서비스 날짜다.</b> 오늘로 잡으면 어제 판 체결을 오늘 오전에
	 * 열었을 때 {@code READY}였던 값이 이 값으로 되돌아간다.
	 */
	NOT_YET,

	/**
	 * 확정 집계 행은 있는데 모집단 표본이 부족하다 ({@code holderCount < 5}). 집단 비교 전용이다.
	 *
	 * <p>모집단 지표 3종은 {@code null}이지만 {@code yourMinutesToSell}은 본인 값이라 채운다(§C-4).
	 */
	INSUFFICIENT_SAMPLE,

	/**
	 * 보유 구간에 변동 카드가 0건이다. 집단 비교 전용이며 {@code priceMoveId}까지 전 필드가 {@code null}이다.
	 *
	 * <p><b>1순위로 판정한다</b>(§C-4) — 기준 카드가 없으면 {@code price_move_peer_stats} 행이 애초에 생기지
	 * 않으므로, 행 존재만 보면 이 흔한 경우가 영원히 {@code NOT_YET}이 된다.
	 */
	NO_EVENT
}
