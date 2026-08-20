// 공개 시점이 지난 튜토리얼 가상 사건 하나를 담는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

/**
 * <b>시각을 담지 않는다.</b> 대본 사건의 공개 시점은 <b>대본 커서</b>가 정하는데 응답의
 * {@code virtualDateTime}은 {@code anchorAt} 기준 벽시계 파생이라 두 시계가 어긋난다(041 4·5번,
 * 이슈 #472). 절대 시각을 붙이려면 두 시계를 맞춰야 하고, 맞추지 않은 채 내려보내면 사건이 가격보다
 * 앞서거나 뒤처져 보이는 조합이 생긴다.
 *
 * <p>대신 <b>목록의 순서가 공개 순서</b>다(대본 구간 순서 = 공개 순서). 마지막 항목이 가장 최근에 공개된
 * 사건이므로 화면은 "방금"·"조금 전" 같은 상대 표현으로 그린다.
 *
 * @param stage   사건이 속한 막. {@code PracticeTutorialChartResponse.scenarioStage}와 같은 규칙으로
 *                정규화한 값이다 — 대본 내부 구간이 아니라 act 단위다
 * @param headline 대본에 사전 확정된 고정 문안. 실행 시점 LLM 생성이 아니며 {@code [연습]} 접두로 문안
 *                자체가 가상임을 밝힌다(SCENARIO-017)
 */
public record PracticeScenarioEventResponse(String stage, String headline) {
}
