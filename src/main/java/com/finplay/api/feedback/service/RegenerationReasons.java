// 매도 회고 서술을 다시 만드는 두 사유(투자일기·흐름과 집단 비교)의 성립 여부 묶음.
package com.finplay.api.feedback.service;

/**
 * 판정은 {@code PostSellFeedbackService}가 하고 반영은 {@code TradeFeedbackWriter}가 한다 (§FEED-013 결정 3).
 *
 * <p><b>둘 다 참이어도 LLM은 한 번만 부른다.</b> 프롬프트에 매도 후 흐름·집단 비교·일기가 모두 실리므로 한 번의
 * 생성이 두 사유를 함께 반영한다. 그 대신 <b>카운터는 둘 다 오른다</b> — 한 번의 생성으로 두 사유를 함께
 * 소비했기 때문이다.
 *
 * <p><b>{@code boolean} 두 개를 그대로 넘기지 않고 이 타입을 두는 이유</b>는 전치(轉置) 때문이다. 두 사유가
 * 서로 다른 카운터·서로 다른 상한·서로 다른 확정 플래그 취급을 갖는데, 인자 순서가 바뀌면
 * {@code journal_regenerations}와 {@code regeneration_attempts}가 조용히 맞바뀐다 — 응답은 정상 200이고 예외도
 * 로그도 없다. 이 이슈에서 가장 조용히 깨지는 자리라 이름으로 고정한다.
 *
 * @param journal 저장된 지문과 현재 일기 지문이 다르고 {@code journal_regenerations}가
 *     {@code max-journal-regeneration} 미만이다. <b>{@code narrative_finalized}를 보지 않는다</b>
 * @param gate    §C-5의 재생성 게이트가 열렸고 아직 확정 전이며 {@code regeneration_attempts}가
 *     {@code max-narrative-retry} 미만이다
 */
record RegenerationReasons(boolean journal, boolean gate) {

	boolean any() {
		return journal || gate;
	}
}
